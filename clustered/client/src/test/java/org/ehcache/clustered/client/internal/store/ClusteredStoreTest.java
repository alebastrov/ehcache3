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

package org.ehcache.clustered.client.internal.store;

import org.ehcache.clustered.client.config.ClusteredResourcePool;
import org.ehcache.clustered.client.config.builders.ClusteredResourcePoolBuilder;
import org.ehcache.clustered.client.internal.EhcacheClientEntity;
import org.ehcache.clustered.client.internal.EhcacheClientEntityFactory;
import org.ehcache.clustered.client.internal.UnitTestConnectionService;
import org.ehcache.clustered.client.internal.store.operations.ChainResolver;
import org.ehcache.clustered.client.internal.store.operations.codecs.OperationsCodec;
import org.ehcache.clustered.common.ServerSideConfiguration;
import org.ehcache.clustered.common.ServerStoreConfiguration;
import org.ehcache.clustered.common.messages.ServerStoreMessageFactory;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.core.spi.store.Store;
import org.ehcache.core.statistics.StoreOperationOutcomes;
import org.ehcache.impl.serialization.LongSerializer;
import org.ehcache.impl.serialization.StringSerializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.terracotta.connection.Connection;

import java.net.URI;
import java.util.Collections;
import java.util.Properties;

import static org.ehcache.clustered.util.StatisticsTestUtils.validateStat;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.*;

public class ClusteredStoreTest {

  private static final String CACHE_IDENTIFIER = "testCache";
  private static final URI CLUSTER_URI = URI.create("terracotta://localhost:9510");

  ClusteredStore<Long, String> store;

  @Before
  public void setup() throws Exception {
    UnitTestConnectionService.add(
        CLUSTER_URI,
        new UnitTestConnectionService.PassthroughServerBuilder().resource("defaultResource", 8, MemoryUnit.MB).build()
    );

    Connection connection = new UnitTestConnectionService().connect(CLUSTER_URI, new Properties());
    EhcacheClientEntityFactory entityFactory = new EhcacheClientEntityFactory(connection);

    ServerSideConfiguration serverConfig =
        new ServerSideConfiguration("defaultResource", Collections.<String, ServerSideConfiguration.Pool>emptyMap());
    entityFactory.create("TestCacheManager", serverConfig);

    EhcacheClientEntity clientEntity = entityFactory.retrieve("TestCacheManager", serverConfig);
    ClusteredResourcePool resourcePool = ClusteredResourcePoolBuilder.fixed(4, MemoryUnit.MB);
    ServerStoreConfiguration serverStoreConfiguration =
        new ServerStoreConfiguration(resourcePool.getPoolAllocation(),
            Long.class.getName(), String.class.getName(),
            Long.class.getName(), String.class.getName(),
            LongSerializer.class.getName(), StringSerializer.class.getName(),
            null
    );
    clientEntity.createCache(CACHE_IDENTIFIER, serverStoreConfiguration);
    ServerStoreMessageFactory factory = new ServerStoreMessageFactory(CACHE_IDENTIFIER);
    ServerStoreProxy serverStoreProxy = new NoInvalidationServerStoreProxy(factory, clientEntity);

    OperationsCodec<Long, String> codec = new OperationsCodec<Long, String>(new LongSerializer(), new StringSerializer());
    ChainResolver<Long, String> resolver = new ChainResolver<Long, String>(codec);
    store = new ClusteredStore<Long, String>(codec, resolver, serverStoreProxy);
  }

  @After
  public void tearDown() throws Exception {
    UnitTestConnectionService.remove("terracotta://localhost:9510/my-application?auto-create");
  }

  @Test
  public void testPut() throws Exception {
    assertThat(store.put(1L, "one"), is(Store.PutStatus.PUT));
    assertThat(store.put(1L, "another one"), is(Store.PutStatus.UPDATE));
    assertThat(store.put(1L, "yet another one"), is(Store.PutStatus.UPDATE));
    validateStat(store, StoreOperationOutcomes.PutOutcome.REPLACED, 2);
    validateStat(store, StoreOperationOutcomes.PutOutcome.PUT, 1);
  }

  @Test
  public void testGet() throws Exception {
    assertThat(store.get(1L), nullValue());
    store.put(1L, "one");
    assertThat(store.get(1L).value(), is("one"));
    validateStat(store, StoreOperationOutcomes.GetOutcome.HIT, 1);
    validateStat(store, StoreOperationOutcomes.GetOutcome.MISS, 1);
  }

  @Test
  public void testContainsKey() throws Exception {
    assertThat(store.containsKey(1L), is(false));
    store.put(1L, "one");
    assertThat(store.containsKey(1L), is(true));
    validateStat(store, StoreOperationOutcomes.GetOutcome.HIT, 0);
    validateStat(store, StoreOperationOutcomes.GetOutcome.MISS, 0);
  }

  @Test
  public void testRemove() throws Exception {
    assertThat(store.remove(1L), is(false));
    store.put(1L, "one");
    assertThat(store.remove(1L), is(true));
    assertThat(store.containsKey(1L), is(false));
    validateStat(store, StoreOperationOutcomes.RemoveOutcome.REMOVED, 1);
    validateStat(store, StoreOperationOutcomes.RemoveOutcome.MISS, 1);
  }

  @Test
  public void testClear() throws Exception {
    assertThat(store.containsKey(1L), is(false));
    store.clear();
    assertThat(store.containsKey(1L), is(false));

    store.put(1L, "one");
    store.put(2L, "two");
    store.put(3L, "three");
    assertThat(store.containsKey(1L), is(true));

    store.clear();

    assertThat(store.containsKey(1L), is(false));
    assertThat(store.containsKey(2L), is(false));
    assertThat(store.containsKey(3L), is(false));
  }

  @Test
  public void testPutIfAbsent() throws Exception {
    assertThat(store.putIfAbsent(1L, "one"), nullValue());
    validateStat(store, StoreOperationOutcomes.PutIfAbsentOutcome.PUT, 1);
    assertThat(store.putIfAbsent(1L, "another one").value(), is("one"));
    validateStat(store, StoreOperationOutcomes.PutIfAbsentOutcome.HIT, 1);
  }

  @Test
  public void testConditionalRemove() throws Exception {
    assertThat(store.remove(1L, "one"), is(Store.RemoveStatus.KEY_MISSING));
    store.put(1L, "one");
    assertThat(store.remove(1L, "one"), is(Store.RemoveStatus.REMOVED));
    store.put(1L, "another one");
    assertThat(store.remove(1L, "one"), is(Store.RemoveStatus.KEY_PRESENT));
    validateStat(store, StoreOperationOutcomes.ConditionalRemoveOutcome.REMOVED, 1);
    validateStat(store, StoreOperationOutcomes.ConditionalRemoveOutcome.MISS, 2);
  }
}