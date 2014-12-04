/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.lang.*;
import org.gridgain.grid.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.marshaller.optimized.*;
import org.gridgain.grid.resources.*;
import org.gridgain.grid.spi.collision.jobstealing.*;
import org.gridgain.grid.spi.failover.jobstealing.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.testframework.config.*;
import org.gridgain.testframework.junits.common.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Job stealing test.
 */
@SuppressWarnings("unchecked")
@GridCommonTest(group = "Kernal Self")
public class GridJobStealingSelfTest extends GridCommonAbstractTest {
    /** Task execution timeout in milliseconds. */
    private static final int TASK_EXEC_TIMEOUT_MS = 50000;

    /** */
    private Ignite ignite1;

    /** */
    private Ignite ignite2;

    /** Job distribution map. Records which job has run on which node. */
    private static Map<UUID, Collection<ComputeJob>> jobDistrMap = new HashMap<>();

    /** */
    public GridJobStealingSelfTest() {
        super(false /* don't start grid*/);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        jobDistrMap.clear();

        ignite1 = startGrid(1);

        ignite2 = startGrid(2);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        ignite1 = null;
        ignite2 = null;
    }

    /**
     * Test 2 jobs on 1 node.
     *
     * @throws GridException If test failed.
     */
    public void testTwoJobs() throws GridException {
        executeAsync(ignite1.compute(), new JobStealingSingleNodeTask(2), null).get(TASK_EXEC_TIMEOUT_MS);

        // Verify that 1 job was stolen by second node.
        assertEquals(2, jobDistrMap.keySet().size());
        assertEquals(1, jobDistrMap.get(ignite1.cluster().localNode().id()).size());
        assertEquals(1, jobDistrMap.get(ignite2.cluster().localNode().id()).size());
    }

    /**
     * Test 2 jobs on 1 node with null predicate.
     *
     * @throws GridException If test failed.
     */
    @SuppressWarnings("NullArgumentToVariableArgMethod")
    public void testTwoJobsNullPredicate() throws GridException {
        executeAsync(ignite1.compute(), new JobStealingSingleNodeTask(2), null).get(TASK_EXEC_TIMEOUT_MS);

        // Verify that 1 job was stolen by second node.
        assertEquals(2, jobDistrMap.keySet().size());
        assertEquals(1, jobDistrMap.get(ignite1.cluster().localNode().id()).size());
        assertEquals(1, jobDistrMap.get(ignite2.cluster().localNode().id()).size());
    }

    /**
     * Test 2 jobs on 1 node with null predicate using string task name.
     *
     * @throws GridException If test failed.
     */
    @SuppressWarnings("NullArgumentToVariableArgMethod")
    public void testTwoJobsTaskNameNullPredicate() throws GridException {
        executeAsync(ignite1.compute(), JobStealingSingleNodeTask.class.getName(), null).get(TASK_EXEC_TIMEOUT_MS);

        // Verify that 1 job was stolen by second node.
        assertEquals(2, jobDistrMap.keySet().size());
        assertEquals(1, jobDistrMap.get(ignite1.cluster().localNode().id()).size());
        assertEquals(1, jobDistrMap.get(ignite2.cluster().localNode().id()).size());
    }

    /**
     * Test 2 jobs on 1 node when one of the predicates is null.
     *
     * @throws GridException If test failed.
     */
    @SuppressWarnings("unchecked")
    public void testTwoJobsPartiallyNullPredicate() throws GridException {
        IgnitePredicate<ClusterNode> topPred =  new IgnitePredicate<ClusterNode>() {
                @Override public boolean apply(ClusterNode e) {
                    return ignite2.cluster().localNode().id().equals(e.id()); // Limit projection with only grid2.
                }
            };

        executeAsync(compute(ignite1.cluster().forPredicate(topPred)).withTimeout(TASK_EXEC_TIMEOUT_MS),
            new JobStealingSpreadTask(2), null).get(TASK_EXEC_TIMEOUT_MS);

        assertEquals(1, jobDistrMap.keySet().size());
        assertEquals(2, jobDistrMap.get(ignite2.cluster().localNode().id()).size());
        assertFalse(jobDistrMap.containsKey(ignite1.cluster().localNode().id()));
    }

    /**
     * Tests that projection predicate is taken into account by Stealing SPI.
     *
     * @throws Exception If failed.
     */
    public void testProjectionPredicate() throws Exception {
        final Ignite ignite3 = startGrid(3);

        executeAsync(compute(ignite1.cluster().forPredicate(new P1<ClusterNode>() {
            @Override public boolean apply(ClusterNode e) {
                return ignite1.cluster().localNode().id().equals(e.id()) ||
                    ignite3.cluster().localNode().id().equals(e.id()); // Limit projection with only grid1 or grid3 node.
            }
        })), new JobStealingSpreadTask(4), null).get(TASK_EXEC_TIMEOUT_MS);

        // Verify that jobs were run only on grid1 and grid3 (not on grid2)
        assertEquals(2, jobDistrMap.keySet().size());
        assertEquals(2, jobDistrMap.get(ignite1.cluster().localNode().id()).size());
        assertEquals(2, jobDistrMap.get(ignite3.cluster().localNode().id()).size());
        assertFalse(jobDistrMap.containsKey(ignite2.cluster().localNode().id()));
    }

    /**
     * Tests that projection predicate is taken into account by Stealing SPI,
     * and that jobs in projection can steal tasks from each other.
     *
     * @throws Exception If failed.
     */
    public void testProjectionPredicateInternalStealing() throws Exception {
        final Ignite ignite3 = startGrid(3);

        IgnitePredicate<ClusterNode> p = new P1<ClusterNode>() {
            @Override public boolean apply(ClusterNode e) {
                return ignite1.cluster().localNode().id().equals(e.id()) ||
                    ignite3.cluster().localNode().id().equals(e.id()); // Limit projection with only grid1 or grid3 node.
            }
        };

        executeAsync(compute(ignite1.cluster().forPredicate(p)), new JobStealingSingleNodeTask(4), null).get(TASK_EXEC_TIMEOUT_MS);

        // Verify that jobs were run only on grid1 and grid3 (not on grid2)
        assertEquals(2, jobDistrMap.keySet().size());
        assertFalse(jobDistrMap.containsKey(ignite2.cluster().localNode().id()));
    }

    /**
     * Tests that a job is not cancelled if there are no
     * available thief nodes in topology.
     *
     * @throws Exception If failed.
     */
    public void testSingleNodeTopology() throws Exception {
        IgnitePredicate<ClusterNode> p = new IgnitePredicate<ClusterNode>() {
            @Override public boolean apply(ClusterNode e) {
                return ignite1.cluster().localNode().id().equals(e.id()); // Limit projection with only grid1 node.
            }
        };

        executeAsync(compute(ignite1.cluster().forPredicate(p)), new JobStealingSpreadTask(2), null).
            get(TASK_EXEC_TIMEOUT_MS);

        assertEquals(1, jobDistrMap.keySet().size());
        assertEquals(2, jobDistrMap.get(ignite1.cluster().localNode().id()).size());
    }

    /**
     * Tests that a job is not cancelled if there are no
     * available thief nodes in projection.
     *
     * @throws Exception If failed.
     */
    public void testSingleNodeProjection() throws Exception {
        ClusterGroup prj = ignite1.cluster().forNodeIds(Collections.singleton(ignite1.cluster().localNode().id()));

        executeAsync(compute(prj), new JobStealingSpreadTask(2), null).get(TASK_EXEC_TIMEOUT_MS);

        assertEquals(1, jobDistrMap.keySet().size());
        assertEquals(2, jobDistrMap.get(ignite1.cluster().localNode().id()).size());
    }

    /**
     * Tests that a job is not cancelled if there are no
     * available thief nodes in projection. Uses null predicate.
     *
     * @throws Exception If failed.
     */
    @SuppressWarnings("NullArgumentToVariableArgMethod")
    public void testSingleNodeProjectionNullPredicate() throws Exception {
        ClusterGroup prj = ignite1.cluster().forNodeIds(Collections.singleton(ignite1.cluster().localNode().id()));

        executeAsync(compute(prj).withTimeout(TASK_EXEC_TIMEOUT_MS), new JobStealingSpreadTask(2), null).
            get(TASK_EXEC_TIMEOUT_MS);

        assertEquals(1, jobDistrMap.keySet().size());
        assertEquals(2, jobDistrMap.get(ignite1.cluster().localNode().id()).size());
    }

    /**
     * Tests job stealing with peer deployment and different class loaders.
     *
     * @throws Exception If failed.
     */
    @SuppressWarnings("unchecked")
    public void testProjectionPredicateDifferentClassLoaders() throws Exception {
        final Ignite ignite3 = startGrid(3);

        URL[] clsLdrUrls;
        try {
            clsLdrUrls = new URL[] {new URL(GridTestProperties.getProperty("p2p.uri.cls"))};
        }
        catch (MalformedURLException e) {
            throw new RuntimeException("Define property p2p.uri.cls", e);
        }

        ClassLoader ldr1 = new URLClassLoader(clsLdrUrls, getClass().getClassLoader());

        Class taskCls = ldr1.loadClass("org.gridgain.grid.tests.p2p.JobStealingTask");
        Class nodeFilterCls = ldr1.loadClass("org.gridgain.grid.tests.p2p.GridExcludeNodeFilter");

        IgnitePredicate<ClusterNode> nodeFilter = (IgnitePredicate<ClusterNode>)nodeFilterCls
            .getConstructor(UUID.class).newInstance(ignite2.cluster().localNode().id());

        Map<UUID, Integer> ret = (Map<UUID, Integer>)executeAsync(compute(ignite1.cluster().forPredicate(nodeFilter)),
            taskCls, null).get(TASK_EXEC_TIMEOUT_MS);

        assert ret != null;
        assert ret.get(ignite1.cluster().localNode().id()) != null && ret.get(ignite1.cluster().localNode().id()) == 2 :
            ret.get(ignite1.cluster().localNode().id());
        assert ret.get(ignite3.cluster().localNode().id()) != null && ret.get(ignite3.cluster().localNode().id()) == 2 :
            ret.get(ignite3.cluster().localNode().id());
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        GridJobStealingCollisionSpi colSpi = new GridJobStealingCollisionSpi();

        // One job at a time.
        colSpi.setActiveJobsThreshold(1);
        colSpi.setWaitJobsThreshold(0);

        GridJobStealingFailoverSpi failSpi = new GridJobStealingFailoverSpi();

        // Verify defaults.
        assert failSpi.getMaximumFailoverAttempts() == GridJobStealingFailoverSpi.DFLT_MAX_FAILOVER_ATTEMPTS;

        cfg.setCollisionSpi(colSpi);
        cfg.setFailoverSpi(failSpi);

        cfg.setMarshaller(new GridOptimizedMarshaller(false));

        return cfg;
    }

    /**
     * Job stealing task, that spreads jobs equally over the grid.
     */
    private static class JobStealingSpreadTask extends GridComputeTaskAdapter<Object, Object> {
        /** Grid. */
        @GridInstanceResource
        private Ignite ignite;

        /** Logger. */
        @GridLoggerResource
        private GridLogger log;

        /** Number of jobs to spawn from task. */
        protected final int nJobs;

        /**
         * Constructs a new task instance.
         *
         * @param nJobs Number of jobs to spawn from this task.
         */
        JobStealingSpreadTask(int nJobs) {
            this.nJobs = nJobs;
        }

        /** {@inheritDoc} */
        @SuppressWarnings("ForLoopReplaceableByForEach")
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid,
            @Nullable Object arg) throws GridException {
            //assert subgrid.size() == 2 : "Invalid subgrid size: " + subgrid.size();

            Map<ComputeJobAdapter, ClusterNode> map = new HashMap<>(subgrid.size());

            Iterator<ClusterNode> subIter = subgrid.iterator();

            // Spread jobs over subgrid.
            for (int i = 0; i < nJobs; i++) {
                if (!subIter.hasNext())
                    subIter = subgrid.iterator(); // wrap around

                map.put(new GridJobStealingJob(5000L), subIter.next());
            }

            return map;
        }

        /** {@inheritDoc} */
        @SuppressWarnings("SuspiciousMethodCalls")
        @Override public Object reduce(List<ComputeJobResult> results) throws GridException {
            for (ComputeJobResult res : results) {
                log.info("Job result: " + res.getData());
            }

            return null;
        }
    }

    /**
     * Job stealing task, that puts all jobs onto one node.
     */
    private static class JobStealingSingleNodeTask extends JobStealingSpreadTask {
        /** {@inheritDoc} */
        JobStealingSingleNodeTask(int nJobs) {
            super(nJobs);
        }

        /**
         * Default constructor.
         *
         * Uses 2 jobs.
         */
        JobStealingSingleNodeTask() {
            super(2);
        }

        /** {@inheritDoc} */
        @SuppressWarnings("ForLoopReplaceableByForEach")
        @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid,
            @Nullable Object arg) throws GridException {
            assert subgrid.size() > 1 : "Invalid subgrid size: " + subgrid.size();

            Map<ComputeJobAdapter, ClusterNode> map = new HashMap<>(subgrid.size());

            // Put all jobs onto one node.
            for (int i = 0; i < nJobs; i++)
                map.put(new GridJobStealingJob(5000L), subgrid.get(0));

            return map;
        }
    }

    /**
     * Job stealing job.
     */
    private static final class GridJobStealingJob extends ComputeJobAdapter {
        /** Injected grid. */
        @GridInstanceResource
        private Ignite ignite;

        /** Logger. */
        @GridLoggerResource
        private GridLogger log;

        /**
         * @param arg Job argument.
         */
        GridJobStealingJob(Long arg) {
            super(arg);
        }

        /** {@inheritDoc} */
        @Override public Serializable execute() throws GridException {
            log.info("Started job on node: " + ignite.cluster().localNode().id());

            if (!jobDistrMap.containsKey(ignite.cluster().localNode().id())) {
                Collection<ComputeJob> jobs = new ArrayList<>();
                jobs.add(this);

                jobDistrMap.put(ignite.cluster().localNode().id(), jobs);
            }
            else
                jobDistrMap.get(ignite.cluster().localNode().id()).add(this);

            try {
                Long sleep = argument(0);

                assert sleep != null;

                Thread.sleep(sleep);
            }
            catch (InterruptedException e) {
                log.info("Job got interrupted on node: " + ignite.cluster().localNode().id());

                throw new GridException("Job got interrupted.", e);
            }
            finally {
                log.info("Job finished on node: " + ignite.cluster().localNode().id());
            }

            return ignite.cluster().localNode().id();
        }
    }
}
