/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.visor.node;

import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.events.*;
import org.apache.ignite.lang.*;
import org.gridgain.grid.*;
import org.gridgain.grid.kernal.processors.task.*;
import org.gridgain.grid.kernal.visor.*;
import org.gridgain.grid.kernal.visor.event.*;
import org.gridgain.grid.kernal.visor.util.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;

import static org.apache.ignite.events.GridEventType.*;

/**
 * Task that runs on specified node and returns events data.
 */
@GridInternal
public class VisorNodeEventsCollectorTask extends VisorMultiNodeTask<VisorNodeEventsCollectorTask.VisorNodeEventsCollectorTaskArg,
    Iterable<? extends VisorGridEvent>, Collection<? extends VisorGridEvent>> {
    /** */
    private static final long serialVersionUID = 0L;

    /** {@inheritDoc} */
    @Override protected VisorEventsCollectJob job(VisorNodeEventsCollectorTaskArg arg) {
        return new VisorEventsCollectJob(arg);
    }

    /** {@inheritDoc} */
    @Override public Iterable<? extends VisorGridEvent> reduce(
        List<ComputeJobResult> results) throws GridException {

        Collection<VisorGridEvent> allEvents = new ArrayList<>();

        for (ComputeJobResult r : results) {
            if (r.getException() == null)
                allEvents.addAll((Collection<VisorGridEvent>) r.getData());
        }

        return allEvents.isEmpty() ? Collections.<VisorGridEvent>emptyList() : allEvents;
    }

    /**
     * Argument for task returns events data.
     */
    @SuppressWarnings("PublicInnerClass")
    public static class VisorNodeEventsCollectorTaskArg implements Serializable {
        /** */
        private static final long serialVersionUID = 0L;

        /** Node local storage key. */
        private final String keyOrder;

        /** Arguments for type filter. */
        private final int[] typeArg;

        /** Arguments for time filter. */
        private final Long timeArg;

        /** Task or job events with task name contains. */
        private final String taskName;

        /** Task or job events with session. */
        private final IgniteUuid taskSessionId;

        /**
         * @param keyOrder Arguments for node local storage key.
         * @param typeArg Arguments for type filter.
         * @param timeArg Arguments for time filter.
         * @param taskName Arguments for task name filter.
         * @param taskSessionId Arguments for task session filter.
         */
        public VisorNodeEventsCollectorTaskArg(@Nullable String keyOrder, @Nullable int[] typeArg,
            @Nullable Long timeArg,
            @Nullable String taskName, @Nullable IgniteUuid taskSessionId) {
            this.keyOrder = keyOrder;
            this.typeArg = typeArg;
            this.timeArg = timeArg;
            this.taskName = taskName;
            this.taskSessionId = taskSessionId;
        }

        /**
         * @param typeArg Arguments for type filter.
         * @param timeArg Arguments for time filter.
         */
        public static VisorNodeEventsCollectorTaskArg createEventsArg(@Nullable int[] typeArg, @Nullable Long timeArg) {
            return new VisorNodeEventsCollectorTaskArg(null, typeArg, timeArg, null, null);
        }

        /**
         * @param timeArg Arguments for time filter.
         * @param taskName Arguments for task name filter.
         * @param taskSessionId Arguments for task session filter.
         */
        public static VisorNodeEventsCollectorTaskArg createTasksArg(@Nullable Long timeArg, @Nullable String taskName,
            @Nullable IgniteUuid taskSessionId) {
            return new VisorNodeEventsCollectorTaskArg(null,
                VisorTaskUtils.concat(EVTS_JOB_EXECUTION, EVTS_TASK_EXECUTION, EVTS_AUTHENTICATION, EVTS_AUTHORIZATION,
                    EVTS_SECURE_SESSION),
                timeArg, taskName, taskSessionId);
        }

        /**
         * @param keyOrder Arguments for node local storage key.
         * @param typeArg Arguments for type filter.
         */
        public static VisorNodeEventsCollectorTaskArg createLogArg(@Nullable String keyOrder, @Nullable int[] typeArg) {
            return new VisorNodeEventsCollectorTaskArg(keyOrder, typeArg, null, null, null);
        }

        /**
         * @return Node local storage key.
         */
        @Nullable public String keyOrder() {
            return keyOrder;
        }

        /**
         * @return Arguments for type filter.
         */
        public int[] typeArgument() {
            return typeArg;
        }

        /**
         * @return Arguments for time filter.
         */
        public Long timeArgument() {
            return timeArg;
        }

        /**
         * @return Task or job events with task name contains.
         */
        public String taskName() {
            return taskName;
        }

        /**
         * @return Task or job events with session.
         */
        public IgniteUuid taskSessionId() {
            return taskSessionId;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(VisorNodeEventsCollectorTaskArg.class, this);
        }
    }

    /**
     * Job for task returns events data.
     */
    private static class VisorEventsCollectJob extends VisorJob<VisorNodeEventsCollectorTaskArg,
            Collection<? extends VisorGridEvent>> {
        /** */
        private static final long serialVersionUID = 0L;

        /**
         * Create job with specified argument.
         *
         * @param arg Job argument.
         */
        private VisorEventsCollectJob(VisorNodeEventsCollectorTaskArg arg) {
            super(arg);
        }

        /**
         * Tests whether or not this task has specified substring in its name.
         *
         * @param taskName Task name to check.
         * @param taskClsName Task class name to check.
         * @param s Substring to check.
         */
        private boolean containsInTaskName(String taskName, String taskClsName, String s) {
            assert taskName != null;
            assert taskClsName != null;

            if (taskName.equals(taskClsName)) {
                int idx = taskName.lastIndexOf('.');

                return ((idx >= 0) ? taskName.substring(idx + 1) : taskName).toLowerCase().contains(s);
            }

            return taskName.toLowerCase().contains(s);
        }

        /**
         * Filter events containing visor in it's name.
         *
         * @param e Event
         * @return {@code true} if not contains {@code visor} in task name.
         */
        private boolean filterByTaskName(GridEvent e, String taskName) {
            if (e.getClass().equals(GridTaskEvent.class)) {
                GridTaskEvent te = (GridTaskEvent)e;

                return containsInTaskName(te.taskName(), te.taskClassName(), taskName);
            }

            if (e.getClass().equals(GridJobEvent.class)) {
                GridJobEvent je = (GridJobEvent)e;

                return containsInTaskName(je.taskName(), je.taskName(), taskName);
            }

            if (e.getClass().equals(GridDeploymentEvent.class)) {
                GridDeploymentEvent de = (GridDeploymentEvent)e;

                return de.alias().toLowerCase().contains(taskName);
            }

            return true;
        }

        /**
         * Filter events containing visor in it's name.
         *
         * @param e Event
         * @return {@code true} if not contains {@code visor} in task name.
         */
        private boolean filterByTaskSessionId(GridEvent e, IgniteUuid taskSessionId) {
            if (e.getClass().equals(GridTaskEvent.class)) {
                GridTaskEvent te = (GridTaskEvent)e;

                return te.taskSessionId().equals(taskSessionId);
            }

            if (e.getClass().equals(GridJobEvent.class)) {
                GridJobEvent je = (GridJobEvent)e;

                return je.taskSessionId().equals(taskSessionId);
            }

            return true;
        }

        /** {@inheritDoc} */
        @Override protected Collection<? extends VisorGridEvent> run(final VisorNodeEventsCollectorTaskArg arg)
            throws GridException {
            final long startEvtTime = arg.timeArgument() == null ? 0L : System.currentTimeMillis() - arg.timeArgument();

            final ClusterNodeLocalMap<String, Long> nl = g.nodeLocalMap();

            final Long startEvtOrder = arg.keyOrder() != null && nl.containsKey(arg.keyOrder()) ?
                nl.get(arg.keyOrder()) : -1L;

            Collection<GridEvent> evts = g.events().localQuery(new IgnitePredicate<GridEvent>() {
                @Override public boolean apply(GridEvent event) {
                    return event.localOrder() > startEvtOrder &&
                        (arg.typeArgument() == null || F.contains(arg.typeArgument(), event.type())) &&
                        event.timestamp() >= startEvtTime &&
                        (arg.taskName() == null || filterByTaskName(event, arg.taskName())) &&
                        (arg.taskSessionId() == null || filterByTaskSessionId(event, arg.taskSessionId()));
                }
            });

            Collection<VisorGridEvent> res = new ArrayList<>(evts.size());

            Long maxOrder = startEvtOrder;

            for (GridEvent e : evts) {
                int tid = e.type();
                IgniteUuid id = e.id();
                String name = e.name();
                UUID nid = e.node().id();
                long t = e.timestamp();
                String msg = e.message();
                String shortDisplay = e.shortDisplay();

                maxOrder = Math.max(maxOrder, e.localOrder());

                if (e instanceof GridTaskEvent) {
                    GridTaskEvent te = (GridTaskEvent)e;

                    res.add(new VisorGridTaskEvent(tid, id, name, nid, t, msg, shortDisplay,
                        te.taskName(), te.taskClassName(), te.taskSessionId(), te.internal()));
                }
                else if (e instanceof GridJobEvent) {
                    GridJobEvent je = (GridJobEvent)e;

                    res.add(new VisorGridJobEvent(tid, id, name, nid, t, msg, shortDisplay,
                        je.taskName(), je.taskClassName(), je.taskSessionId(), je.jobId()));
                }
                else if (e instanceof GridDeploymentEvent) {
                    GridDeploymentEvent de = (GridDeploymentEvent)e;

                    res.add(new VisorGridDeploymentEvent(tid, id, name, nid, t, msg, shortDisplay, de.alias()));
                }
                else if (e instanceof GridLicenseEvent) {
                    GridLicenseEvent le = (GridLicenseEvent)e;

                    res.add(new VisorGridLicenseEvent(tid, id, name, nid, t, msg, shortDisplay, le.licenseId()));
                }
                else if (e instanceof GridDiscoveryEvent) {
                    GridDiscoveryEvent de = (GridDiscoveryEvent)e;

                    ClusterNode node = de.eventNode();

                    String addr = F.first(node.addresses());

                    res.add(new VisorGridDiscoveryEvent(tid, id, name, nid, t, msg, shortDisplay,
                        node.id(), addr, node.isDaemon()));
                }
                else if (e instanceof GridAuthenticationEvent) {
                    GridAuthenticationEvent ae = (GridAuthenticationEvent)e;

                    res.add(new VisorGridAuthenticationEvent(tid, id, name, nid, t, msg, shortDisplay, ae.subjectType(),
                        ae.subjectId(), ae.login()));
                }
                else if (e instanceof GridAuthorizationEvent) {
                    GridAuthorizationEvent ae = (GridAuthorizationEvent)e;

                    res.add(new VisorGridAuthorizationEvent(tid, id, name, nid, t, msg, shortDisplay, ae.operation(),
                        ae.subject()));
                }
                else if (e instanceof GridSecureSessionEvent) {
                    GridSecureSessionEvent se = (GridSecureSessionEvent) e;

                    res.add(new VisorGridSecuritySessionEvent(tid, id, name, nid, t, msg, shortDisplay, se.subjectType(),
                        se.subjectId()));
                }
                else
                    res.add(new VisorGridEvent(tid, id, name, nid, t, msg, shortDisplay));
            }

            // Update latest order in node local, if not empty.
            if (arg.keyOrder() != null && !res.isEmpty())
                nl.put(arg.keyOrder(), maxOrder);

            return res;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(VisorEventsCollectJob.class, this);
        }
    }
}
