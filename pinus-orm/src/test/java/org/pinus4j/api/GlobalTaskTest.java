package org.pinus4j.api;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.pinus4j.ApiBaseTest;
import org.pinus4j.api.query.Condition;
import org.pinus4j.api.query.IQuery;
import org.pinus4j.entity.TestGlobalEntity;
import org.pinus4j.task.ITask;
import org.pinus4j.task.TaskFuture;

public class GlobalTaskTest extends ApiBaseTest {

	private Number[] pks;

	private List<TestGlobalEntity> entities;

	private static final int SIZE = 2100;

	@Before
	public void before() {
		// save more
		entities = new ArrayList<TestGlobalEntity>(SIZE);
		TestGlobalEntity entity = null;
		for (int i = 0; i < SIZE; i++) {
			entity = createGlobalEntity();
			entity.setTestString("i am pinus");
			entities.add(entity);
		}
		pks = cacheClient.globalSaveBatch(entities, CLUSTER_KLSTORAGE);
		// check save more
		entities = cacheClient.findGlobalByPks(CLUSTER_KLSTORAGE, TestGlobalEntity.class, pks);
		Assert.assertEquals(SIZE, entities.size());
	}

	@After
	public void after() {
		// remove more
		cacheClient.globalRemoveByPks(CLUSTER_KLSTORAGE, TestGlobalEntity.class, pks);
	}

	@Test
	public void testSubmit() throws InterruptedException {
		ITask<TestGlobalEntity> task = new SimpleGlobalTask();

		TaskFuture future = cacheClient.submit(task, TestGlobalEntity.class);
		while (!future.isDone()) {
			System.out.println(future.getProgress());
		}

		System.out.println(future);
	}

	@Test
	public void testSubmitQuery() throws InterruptedException {
		ITask<TestGlobalEntity> task = new SimpleGlobalTask();
		IQuery query = cacheClient.createQuery();
		query.add(Condition.gt("testInt", 100));

		TaskFuture future = cacheClient.submit(task, TestGlobalEntity.class, query);
		future.await();

		System.out.println(future);
	}

	public static class SimpleGlobalTask extends AbstractTask<TestGlobalEntity> {
		@Override
		public void batchRecord(List<TestGlobalEntity> entityList) {
			for (TestGlobalEntity entity : entityList) {
			}
		}
	}

}