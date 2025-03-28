/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.search.mapper.orm.session.impl;

import java.util.Optional;
import java.util.function.Function;

import jakarta.transaction.Synchronization;

import org.hibernate.Transaction;
import org.hibernate.search.engine.cfg.ConfigurationPropertySource;
import org.hibernate.search.engine.cfg.spi.ConfigurationProperty;
import org.hibernate.search.engine.cfg.spi.OptionalConfigurationProperty;
import org.hibernate.search.engine.environment.bean.BeanHolder;
import org.hibernate.search.engine.environment.bean.BeanReference;
import org.hibernate.search.mapper.orm.automaticindexing.impl.AutomaticIndexingStrategyStartContext;
import org.hibernate.search.mapper.orm.automaticindexing.impl.HibernateOrmIndexingQueueEventSendingPlan;
import org.hibernate.search.mapper.orm.automaticindexing.spi.AutomaticIndexingEventSendingSessionContext;
import org.hibernate.search.mapper.orm.automaticindexing.spi.AutomaticIndexingQueueEventSendingPlan;
import org.hibernate.search.mapper.orm.cfg.HibernateOrmMapperSettings;
import org.hibernate.search.mapper.orm.event.impl.HibernateOrmListenerContextProvider;
import org.hibernate.search.mapper.orm.event.impl.HibernateSearchEventListener;
import org.hibernate.search.mapper.orm.logging.impl.ConfigurationLog;
import org.hibernate.search.mapper.orm.logging.impl.DeprecationLog;
import org.hibernate.search.mapper.pojo.work.IndexingPlanSynchronizationStrategy;
import org.hibernate.search.mapper.pojo.work.spi.ConfiguredIndexingPlanSynchronizationStrategy;
import org.hibernate.search.mapper.pojo.work.spi.PojoIndexingPlan;
import org.hibernate.search.mapper.pojo.work.spi.PojoIndexingQueueEventProcessingPlan;
import org.hibernate.search.util.common.impl.Closer;

public final class ConfiguredAutomaticIndexingStrategy {

	@SuppressWarnings("deprecation")
	private static final OptionalConfigurationProperty<Boolean> AUTOMATIC_INDEXING_ENABLED =
			ConfigurationProperty.forKey( HibernateOrmMapperSettings.Radicals.AUTOMATIC_INDEXING_ENABLED )
					.asBoolean()
					.build();

	private static final OptionalConfigurationProperty<Boolean> INDEXING_LISTENERS_ENABLED =
			ConfigurationProperty.forKey( HibernateOrmMapperSettings.Radicals.INDEXING_LISTENERS_ENABLED )
					.asBoolean()
					.build();

	@SuppressWarnings("deprecation")
	private static final OptionalConfigurationProperty<Boolean> AUTOMATIC_INDEXING_ENABLED_LEGACY_STRATEGY =
			ConfigurationProperty.forKey( HibernateOrmMapperSettings.Radicals.AUTOMATIC_INDEXING_STRATEGY )
					.as( Boolean.class,
							v -> !org.hibernate.search.mapper.orm.automaticindexing.AutomaticIndexingStrategyName.NONE
									.equals( org.hibernate.search.mapper.orm.automaticindexing.AutomaticIndexingStrategyName
											.of( v ) ) )
					.build();

	@SuppressWarnings("deprecation")
	private static final OptionalConfigurationProperty<BeanReference<
			? extends org.hibernate.search.mapper.orm.automaticindexing.session.AutomaticIndexingSynchronizationStrategy>> AUTOMATIC_INDEXING_SYNCHRONIZATION_STRATEGY =
					ConfigurationProperty
							.forKey( HibernateOrmMapperSettings.Radicals.AUTOMATIC_INDEXING_SYNCHRONIZATION_STRATEGY )
							.asBeanReference(
									org.hibernate.search.mapper.orm.automaticindexing.session.AutomaticIndexingSynchronizationStrategy.class )
							.build();

	private static final OptionalConfigurationProperty<
			BeanReference<? extends IndexingPlanSynchronizationStrategy>> INDEXING_PLAN_SYNCHRONIZATION_STRATEGY =
					ConfigurationProperty.forKey( HibernateOrmMapperSettings.Radicals.INDEXING_PLAN_SYNCHRONIZATION_STRATEGY )
							.asBeanReference( IndexingPlanSynchronizationStrategy.class )
							.build();

	@SuppressWarnings("deprecation")
	private static final ConfigurationProperty<Boolean> AUTOMATIC_INDEXING_ENABLE_DIRTY_CHECK =
			ConfigurationProperty.forKey( HibernateOrmMapperSettings.Radicals.AUTOMATIC_INDEXING_ENABLE_DIRTY_CHECK )
					.asBoolean()
					.withDefault( HibernateOrmMapperSettings.Defaults.AUTOMATIC_INDEXING_ENABLE_DIRTY_CHECK )
					.build();

	private final Function<AutomaticIndexingEventSendingSessionContext, AutomaticIndexingQueueEventSendingPlan> senderFactory;
	private final boolean enlistsInTransaction;

	private HibernateOrmSearchSessionMappingContext mappingContext;
	private BeanHolder<? extends IndexingPlanSynchronizationStrategy> defaultSynchronizationStrategyHolder;
	private ConfiguredIndexingPlanSynchronizationStrategy defaultSynchronizationStrategy;

	public ConfiguredAutomaticIndexingStrategy(
			Function<AutomaticIndexingEventSendingSessionContext, AutomaticIndexingQueueEventSendingPlan> senderFactory,
			boolean enlistsInTransaction) {
		this.senderFactory = senderFactory;
		this.enlistsInTransaction = enlistsInTransaction;
	}

	public boolean usesAsyncProcessing() {
		return senderFactory != null;
	}

	// Do everything related to runtime configuration or that doesn't involve I/O
	public void start(HibernateOrmSearchSessionMappingContext mappingContext,
			AutomaticIndexingStrategyStartContext startContext,
			HibernateOrmListenerContextProvider contextProvider) {
		this.mappingContext = mappingContext;
		ConfigurationPropertySource configurationSource = startContext.configurationPropertySource();

		resolveDefaultSyncStrategyHolder( startContext );

		defaultSynchronizationStrategy = configure( defaultSynchronizationStrategyHolder.get() );

		Optional<Boolean> automaticIndexingEnabledOptional = AUTOMATIC_INDEXING_ENABLED.get( configurationSource );
		Optional<Boolean> indexingListenersEnabledOptional = INDEXING_LISTENERS_ENABLED.get( configurationSource );
		if ( automaticIndexingEnabledOptional.isPresent() ) {
			if ( indexingListenersEnabledOptional.isPresent() ) {
				throw DeprecationLog.INSTANCE.bothNewAndOldConfigurationPropertiesForIndexingListenersAreUsed(
						AUTOMATIC_INDEXING_ENABLED.resolveOrRaw( configurationSource ),
						INDEXING_LISTENERS_ENABLED.resolveOrRaw( configurationSource )
				);
			}
			DeprecationLog.INSTANCE.deprecatedPropertyUsedInsteadOfNew(
					AUTOMATIC_INDEXING_ENABLED.resolveOrRaw( configurationSource ),
					INDEXING_LISTENERS_ENABLED.resolveOrRaw( configurationSource )
			);
		}


		if ( automaticIndexingEnabledOptional.orElse( indexingListenersEnabledOptional.orElse(
				HibernateOrmMapperSettings.Defaults.INDEXING_LISTENERS_ENABLED ) )
				&& AUTOMATIC_INDEXING_ENABLED_LEGACY_STRATEGY.getAndMap( configurationSource, enabled -> {
					DeprecationLog.INSTANCE.deprecatedPropertyUsedInsteadOfNew(
							AUTOMATIC_INDEXING_ENABLED_LEGACY_STRATEGY.resolveOrRaw( configurationSource ),
							INDEXING_LISTENERS_ENABLED.resolveOrRaw( configurationSource )
					);
					return enabled;
				} )
						.orElse( true ) ) {
			ConfigurationLog.INSTANCE.hibernateSearchListenerEnabled();
			@SuppressWarnings("deprecation")
			HibernateSearchEventListener hibernateSearchEventListener = new HibernateSearchEventListener(
					contextProvider, AUTOMATIC_INDEXING_ENABLE_DIRTY_CHECK.getAndTransform(
							startContext.configurationPropertySource(), dirtyCheckingEnabled -> {
								//we want to log a warning if the user set a non-default value
								if ( HibernateOrmMapperSettings.Defaults.AUTOMATIC_INDEXING_ENABLE_DIRTY_CHECK != dirtyCheckingEnabled ) {
									DeprecationLog.INSTANCE.automaticIndexingEnableDirtyCheckIsDeprecated(
											AUTOMATIC_INDEXING_ENABLE_DIRTY_CHECK.resolveOrRaw(
													startContext.configurationPropertySource() ) );
								}
								return dirtyCheckingEnabled;
							}
					) );
			hibernateSearchEventListener.registerTo( mappingContext.sessionFactory() );
		}
		else {
			ConfigurationLog.INSTANCE.hibernateSearchListenerDisabled();
		}
	}

	private void resolveDefaultSyncStrategyHolder(AutomaticIndexingStrategyStartContext startContext) {
		ConfigurationPropertySource configurationSource = startContext.configurationPropertySource();
		boolean legacyStrategySet = AUTOMATIC_INDEXING_SYNCHRONIZATION_STRATEGY.get( configurationSource ).isPresent();
		boolean newStrategySet = INDEXING_PLAN_SYNCHRONIZATION_STRATEGY
				.get( configurationSource ).isPresent();

		if ( legacyStrategySet && newStrategySet ) {
			throw DeprecationLog.INSTANCE.bothNewAndOldConfigurationPropertiesForIndexingPlanSyncAreUsed(
					INDEXING_PLAN_SYNCHRONIZATION_STRATEGY.resolveOrRaw( configurationSource ),
					AUTOMATIC_INDEXING_SYNCHRONIZATION_STRATEGY.resolveOrRaw( configurationSource )
			);
		}

		if ( usesAsyncProcessing() ) {
			if ( legacyStrategySet || newStrategySet ) {
				// If we send events to a queue, we're mostly asynchronous
				// and thus configuring the synchronization strategy does not make sense.
				throw ConfigurationLog.INSTANCE.cannotConfigureSynchronizationStrategyWithIndexingEventQueue();
			}

			// We force the synchronization strategy to sync.
			// The commit/refresh strategies will be ignored,
			// but we're only interested in the future handler:
			// we need it to block until the sender is done pushing events to the queue.
			defaultSynchronizationStrategyHolder = BeanHolder.of( IndexingPlanSynchronizationStrategy.writeSync() );
		}
		else if ( legacyStrategySet ) {
			@SuppressWarnings("deprecation")
			BeanHolder<
					? extends org.hibernate.search.mapper.orm.automaticindexing.session.AutomaticIndexingSynchronizationStrategy> holder =
							// Going through the config property source again in order to get context if an error occurs.
							AUTOMATIC_INDEXING_SYNCHRONIZATION_STRATEGY.getAndMap( configurationSource, reference -> {
								DeprecationLog.INSTANCE.automaticIndexingSynchronizationStrategyIsDeprecated(
										AUTOMATIC_INDEXING_SYNCHRONIZATION_STRATEGY.resolveOrRaw( configurationSource ),
										INDEXING_PLAN_SYNCHRONIZATION_STRATEGY.resolveOrRaw( configurationSource ) );
								return startContext.beanResolver().resolve( reference );
							} )
									.get(); // We know this optional is not empty
			defaultSynchronizationStrategyHolder = BeanHolder.of(
					new HibernateOrmIndexingPlanSynchronizationStrategyAdapter( holder.get() )
			).withDependencyAutoClosing( holder );
		}
		else {
			// Going through the config property source again in order to get context if an error occurs.
			defaultSynchronizationStrategyHolder = INDEXING_PLAN_SYNCHRONIZATION_STRATEGY.getAndTransform(
					configurationSource,
					referenceOptional -> startContext.beanResolver().resolve( referenceOptional
							.orElse( HibernateOrmMapperSettings.Defaults.INDEXING_PLAN_SYNCHRONIZATION_STRATEGY ) )
			);
		}
	}

	public void stop() {
		try ( Closer<RuntimeException> closer = new Closer<>() ) {
			closer.push( BeanHolder::close, defaultSynchronizationStrategyHolder );
			defaultSynchronizationStrategy = null;
			defaultSynchronizationStrategyHolder = null;
			mappingContext = null;
		}
	}

	public ConfiguredIndexingPlanSynchronizationStrategy defaultIndexingPlanSynchronizationStrategy() {
		return defaultSynchronizationStrategy;
	}

	public ConfiguredIndexingPlanSynchronizationStrategy configureOverriddenSynchronizationStrategy(
			IndexingPlanSynchronizationStrategy synchronizationStrategy) {
		if ( usesAsyncProcessing() ) {
			throw ConfigurationLog.INSTANCE.cannotConfigureSynchronizationStrategyWithIndexingEventQueue();
		}

		return configure( synchronizationStrategy );
	}

	public PojoIndexingPlan createIndexingPlan(HibernateOrmSearchSession context,
			ConfiguredIndexingPlanSynchronizationStrategy synchronizationStrategy) {
		if ( usesAsyncProcessing() ) {
			AutomaticIndexingQueueEventSendingPlan delegate = senderFactory.apply( context );
			return mappingContext.createIndexingPlan( context, new HibernateOrmIndexingQueueEventSendingPlan( delegate ) );
		}
		else {
			return mappingContext.createIndexingPlan( context,
					synchronizationStrategy.documentCommitStrategy(),
					synchronizationStrategy.documentRefreshStrategy() );
		}
	}

	public Synchronization createTransactionWorkQueueSynchronization(PojoIndexingPlan indexingPlan,
			HibernateOrmSearchSessionHolder sessionProperties,
			Transaction transactionIdentifier,
			ConfiguredIndexingPlanSynchronizationStrategy synchronizationStrategy) {
		if ( enlistsInTransaction ) {
			return new BeforeCommitIndexingPlanSynchronization( indexingPlan, sessionProperties, transactionIdentifier,
					synchronizationStrategy );
		}
		else {
			return new AfterCommitIndexingPlanSynchronization( indexingPlan, sessionProperties, transactionIdentifier,
					synchronizationStrategy );
		}
	}

	public PojoIndexingQueueEventProcessingPlan createIndexingQueueEventProcessingPlan(HibernateOrmSearchSession context,
			ConfiguredIndexingPlanSynchronizationStrategy synchronizationStrategy) {
		AutomaticIndexingQueueEventSendingPlan delegate = senderFactory.apply( context );
		return mappingContext.createIndexingQueueEventProcessingPlan( context,
				synchronizationStrategy.documentCommitStrategy(),
				synchronizationStrategy.documentRefreshStrategy(),
				new HibernateOrmIndexingQueueEventSendingPlan( delegate ) );
	}

	private ConfiguredIndexingPlanSynchronizationStrategy configure(
			IndexingPlanSynchronizationStrategy synchronizationStrategy) {
		ConfiguredIndexingPlanSynchronizationStrategy.Builder builder =
				new ConfiguredIndexingPlanSynchronizationStrategy.Builder(
						mappingContext.failureHandler()
				);
		synchronizationStrategy.apply( builder );
		return builder.build();
	}

}
