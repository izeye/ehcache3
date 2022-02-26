/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehcache.core.internal.statistics;

import org.ehcache.Cache;
import org.ehcache.Status;
import org.ehcache.config.CacheConfiguration;
import org.ehcache.core.InternalCache;
import org.ehcache.core.events.CacheManagerListener;
import org.ehcache.core.spi.service.CacheManagerProviderService;
import org.ehcache.core.spi.service.StatisticsService;
import org.ehcache.core.spi.store.InternalCacheManager;
import org.ehcache.core.spi.store.Store;
import org.ehcache.core.statistics.CacheStatistics;
import org.ehcache.core.statistics.OperationObserver;
import org.ehcache.core.statistics.OperationStatistic;
import org.ehcache.core.statistics.StatisticType;
import org.ehcache.core.statistics.ZeroOperationStatistic;
import org.ehcache.spi.service.Service;
import org.ehcache.spi.service.ServiceDependencies;
import org.ehcache.spi.service.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.statistics.MappedOperationStatistic;
import org.terracotta.statistics.StatisticsManager;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import static org.terracotta.statistics.StatisticBuilder.operation;

/**
 * Default implementation using the statistics calculated by the observers set on the caches.
 */
@ServiceDependencies(CacheManagerProviderService.class)
public class DefaultStatisticsService implements StatisticsService, CacheManagerListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultStatisticsService.class);

  private final ConcurrentMap<String, DefaultCacheStatistics> cacheStatistics = new ConcurrentHashMap<>();

  private volatile InternalCacheManager cacheManager;

  @Override
  public CacheStatistics getCacheStatistics(String cacheName) {
    CacheStatistics stats = cacheStatistics.get(cacheName);
    if (stats == null) {
      throw new IllegalArgumentException("Unknown cache: " + cacheName);
    }
    return stats;
  }

  @Override
  public void registerWithParent(Object toAssociate, Object parent) {
    StatisticsManager.associate(toAssociate).withParent(parent);
  }

  @Override
  public <K, V, S extends Enum<S>, T extends Enum<T>> OperationStatistic<T> registerStoreStatistics(Store<K, V> store, String targetName, int tierHeight, String tag, Map<T, Set<S>> translation, String statisticName) {

    Class<S> outcomeType = getOutcomeType(translation);

    // If the original stat doesn't exist, we do not need to translate it
    if (StatsUtils.hasOperationStat(store, outcomeType, targetName)) {

      MappedOperationStatistic<S, T> operationStatistic = new MappedOperationStatistic<>(store, translation, statisticName, tierHeight, targetName, tag);
      StatisticsManager.associate(operationStatistic).withParent(store);
      return new DelegatedMappedOperationStatistics<>(operationStatistic);
    } else {
      return ZeroOperationStatistic.get();
    }
  }

  /**
   * From the Map of translation, we extract one of the items to get the declaring class of the enum.
   *
   * @param translation translation map
   * @param <S> type of the outcome
   * @param <T> type of the possible translations
   * @return the outcome type
   */
  private static <S extends Enum<S>, T extends Enum<T>> Class<S> getOutcomeType(Map<T, Set<S>> translation) {
    Map.Entry<T, Set<S>> first = translation.entrySet().iterator().next();
    return first.getValue().iterator().next().getDeclaringClass();
  }

  @Override
  public void deRegisterFromParent(Object toDisassociate, Object parent) {
    StatisticsManager.dissociate(toDisassociate).fromParent(parent);
  }

  @Override
  public void cleanForNode(Object node) {
    StatisticsManager.nodeFor(node).clean();
  }

  @Override
  public <T extends Serializable> void registerStatistic(Object context, String name, StatisticType type, Set<String> tags, Supplier<T> valueSupplier) {
    StatisticsManager.createPassThroughStatistic(context, name, tags, convert(type), valueSupplier);
  }

  @Override
  public <T extends Enum<T>> OperationObserver<T> createOperationStatistics(String name, Class<T> outcome, String tag, Object context) {
    return new DelegatingOperationObserver<>(operation(outcome).named(name).of(context).tag(tag).build());
  }

  @Override
  public void start(ServiceProvider<Service> serviceProvider) {
    LOGGER.debug("Starting service");

    CacheManagerProviderService cacheManagerProviderService = serviceProvider.getService(CacheManagerProviderService.class);
    cacheManager = cacheManagerProviderService.getCacheManager();
    cacheManager.registerListener(this);
  }

  @Override
  public void stop() {
    LOGGER.debug("Stopping service");
    cacheManager.deregisterListener(this);
    cacheStatistics.clear();
  }

  @Override
  public void stateTransition(Status from, Status to) {
    LOGGER.debug("Moving from " + from + " to " + to);
    switch (to) {
      case AVAILABLE:
        registerAllCaches();
        break;
      case UNINITIALIZED:
        cacheManager.deregisterListener(this);
        cacheStatistics.clear();
        break;
      case MAINTENANCE:
        throw new IllegalStateException("Should not be started in maintenance mode");
      default:
        throw new AssertionError("Unsupported state: " + to);
    }
  }

  private void registerAllCaches() {
    for (Map.Entry<String, CacheConfiguration<?, ?>> entry : cacheManager.getRuntimeConfiguration().getCacheConfigurations().entrySet()) {
      String alias = entry.getKey();
      CacheConfiguration<?, ?> configuration = entry.getValue();
      Cache<?, ?> cache = cacheManager.getCache(alias, configuration.getKeyType(), configuration.getValueType());
      cacheAdded(alias, cache);
    }
  }

  @Override
  public void cacheAdded(String alias, Cache<?, ?> cache) {
    LOGGER.debug("Cache added " + alias);
    cacheStatistics.put(alias, new DefaultCacheStatistics((InternalCache<?, ?>) cache));
  }

  @Override
  public void cacheRemoved(String alias, Cache<?, ?> cache) {
    LOGGER.debug("Cache removed " + alias);
    cacheStatistics.remove(alias);
  }

  private static org.terracotta.statistics.StatisticType convert(StatisticType type) {
    switch (type) {
      case COUNTER:
        return org.terracotta.statistics.StatisticType.COUNTER;
      case GAUGE:
        return org.terracotta.statistics.StatisticType.GAUGE;
      default:
        throw new IllegalArgumentException("Untranslatable statistic type : " + type);
    }
  }
}
