package my.bookshop.handlers;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.Result;
import com.sap.cds.Struct;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Upsert;
import com.sap.cds.ql.cqn.CqnPredicate;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CdsReadEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.draft.DraftPatchEventContext;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.messaging.TopicMessageEventContext;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cloud.sdk.cloudplatform.resilience.ResilienceConfiguration;
import com.sap.cloud.sdk.cloudplatform.resilience.ResilienceConfiguration.TimeLimiterConfiguration;
import com.sap.cloud.sdk.cloudplatform.resilience.ResilienceDecorator;

import cds.gen.adminservice.Addresses;
import cds.gen.adminservice.Addresses_;
import cds.gen.adminservice.AdminService_;
import cds.gen.adminservice.Orders;
import cds.gen.api_business_partner.ApiBusinessPartner_;
import cds.gen.api_business_partner.BOBusinessPartnerChanged;
import my.bookshop.MessageKeys;

/**
 * Custom handler for the Admin Service Addresses, which come from a remote S/4 System
 */
@Component
@ServiceName(AdminService_.CDS_NAME)
public class AdminServiceAddressHandler implements EventHandler {

	private final static Logger logger = LoggerFactory.getLogger(AdminServiceAddressHandler.class);

	// We are mashing up the AdminService with two other services...
	private final PersistenceService db;
	private final CqnService bupa;

	AdminServiceAddressHandler(PersistenceService db, @Qualifier(ApiBusinessPartner_.CDS_NAME) CqnService bupa) {
		this.db = db;
		this.bupa = bupa;
	}

	// Delegate ValueHelp requests to S/4 backend, fetching current user's addresses from there
	@On(entity = Addresses_.CDS_NAME)
	public Result readAddresses(CdsReadEventContext context) {
		String businessPartner = context.getUserInfo().getAttributeValues("businessPartner").stream().findFirst()
			.orElseThrow(() -> new ServiceException(ErrorStatuses.FORBIDDEN, MessageKeys.BUPA_MISSING));

		// forward the existing selected columns + where condition
		Select<?> select = Select.from(ApiBusinessPartner_.ADDRESSES)
			.columns(context.getCqn().items())
			.where(a -> a.businessPartner().eq(businessPartner));

		Optional<CqnPredicate> where = context.getCqn().where();
		if(where.isPresent()) {
			select.where(CQL.and(select.where().get(), where.get()));
		}

		// use Cloud SDK resilience capabilities..
		ResilienceConfiguration config = ResilienceConfiguration.of(AdminServiceAddressHandler.class)
			.timeLimiterConfiguration(TimeLimiterConfiguration.of(Duration.ofSeconds(10)));

		return ResilienceDecorator.executeSupplier(() ->  {
			// ..to access the S/4 system in a resilient way..
			return bupa.run(select);
		}, config, (t) -> {
			// ..falling back to the already replicated addresses in our own database
			Select<?> selectDb = Select.copy(context.getCqn()).where(select.where().get());
			return db.run(selectDb);
		});
	}

	// Replicate chosen addresses from S/4 when filling orders.
	@Before
	public void patchAddressId(DraftPatchEventContext context, Stream<Orders> orders) {
		String businessPartner = context.getUserInfo().getAttributeValues("businessPartner").stream().findFirst()
			.orElseThrow(() -> new ServiceException(ErrorStatuses.FORBIDDEN, MessageKeys.BUPA_MISSING));

		orders.forEach(order -> {
			// check if the address was updated
			String addressId = order.getShippingAddressId();
			if(addressId != null) {
				Result replica = db.run(Select.from(AdminService_.ADDRESSES).where(a -> a.businessPartner().eq(businessPartner).and(a.ID().eq(addressId))));
				// check if the address was not yet replicated
				if(replica.rowCount() < 1) {
					Result remoteAddresses = bupa.run(Select.from(ApiBusinessPartner_.ADDRESSES)
							.where(a -> a.businessPartner().eq(businessPartner).and(a.ID().eq(addressId))));

					if(remoteAddresses.rowCount() == 1) {
						// TODO in case another parallel transaction also replicates this address this might cause a conflict
						db.run(Upsert.into(AdminService_.ADDRESSES).entries(remoteAddresses));
					} else {
						logger.warn("Unexpected number of shipping addresses for ID '{}': {}", addressId, remoteAddresses.rowCount());
					}
				}
			}
		});
	}

	@On(service = "bupa-messaging", event = "BO/BusinessPartner/Changed")
	public void updateBusinessPartnerAddresses(TopicMessageEventContext context) {
		logger.info(">> received: " + context.getData());
		BOBusinessPartnerChanged payload = Struct.access(payloadMap(context.getData())).as(BOBusinessPartnerChanged.class);
		for(BOBusinessPartnerChanged.Key key : payload.getKey()) {
			String businessPartner = key.getBusinesspartner(); // S/4 HANA's payload format
			if(businessPartner != null) {
				// fetch affected entries from local replicas
				Result replicas = db.run(Select.from(AdminService_.ADDRESSES).where(a -> a.businessPartner().eq(businessPartner)));
				if(replicas.rowCount() > 0) {
					logger.info("Updating Addresses for BusinessPartner '{}'", businessPartner);
					// fetch changed data from S/4 -> might be less than local due to deletes
					Result remoteAddresses = bupa.run(Select.from(ApiBusinessPartner_.ADDRESSES).where(a -> a.businessPartner().eq(businessPartner)));
					// update replicas or add tombstone if external address was deleted
					replicas.streamOf(Addresses.class).forEach(rep -> {
						Optional<Addresses> matching = remoteAddresses
							.streamOf(Addresses.class)
							.filter(ext -> ext.getId().equals(rep.getId()))
							.findFirst();

						if(!matching.isPresent()) {
							rep.setTombstone(true);
						} else {
							rep.replaceAll((k, v) -> matching.get().get(k));
						}
					});
					// update local replicas with changes from S/4
					db.run(Upsert.into(AdminService_.ADDRESSES).entries(replicas));
				}
			}
		}
		context.setCompleted();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> payloadMap(String json) {
		try {
			Map<String, Object> event = new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {});
			if(event.get("data") instanceof Map) {
				return (Map<String, Object>) event.get("data");
			}
			return new HashMap<>();
		} catch (JsonProcessingException e) {
			return new HashMap<>();
		}
	}

}
