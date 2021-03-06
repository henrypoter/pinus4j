package org.pinus4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;

import junit.framework.Assert;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.pinus4j.api.IShardingStorageClient;
import org.pinus4j.cluster.beans.IShardingKey;
import org.pinus4j.cluster.beans.ShardingKey;
import org.pinus4j.entity.TestEntity;

public class LoadTest extends BaseTest {

	private static final CountDownLatch cdl = new CountDownLatch(99999999);

	private static int counter;

	private static IShardingStorageClient storageClient;

	@BeforeClass
	public static void before() {
		storageClient = getStorageClient();
	}

	@AfterClass
	public void after() {
		storageClient.destroy();
	}

	public void loadTest() throws Exception {
		for (int i = 0; i < 200; i++) {
			new Thread() {
				public void run() {
					while (true) {
						TestEntity entity = createEntity();
						Number id = storageClient.save(entity);

						IShardingKey<Number> key = new ShardingKey<Number>(CLUSTER_KLSTORAGE, id);
						storageClient.removeByPk(id, key, TestEntity.class);

						cdl.countDown();
					}
				}
			}.start();
		}

		cdl.await();
	}

	@Test
	public void testCuratorDistributeLock() throws Exception {
		int threadNum = 200;
		final int maxCount = 5;
		int count = threadNum * maxCount;

		final Random r = new Random();

		List<Thread> ts = new ArrayList<Thread>();
		for (int i = 0; i < threadNum; i++) {
			Thread t = new Thread() {
				public void run() {
					for (int i = 0; i < maxCount; i++) {
						Lock lock = storageClient.createLock("junittest");
						try {
							lock.lock();
							System.out.println(counter++);

							Thread.sleep(r.nextInt(10));
						} catch (Exception e) {
							e.printStackTrace();
						} finally {
							try {
								lock.unlock();
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					}
				}
			};
			t.start();
			ts.add(t);
		}

		for (Thread t : ts) {
			t.join();
		}

		Assert.assertEquals(count, counter);
	}

}
