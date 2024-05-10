/*
 * Copyright © ${year} ${owner} (${email})
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
package com.jd.live.agent.governance.invoke.cluster;

import com.jd.live.agent.bootstrap.exception.RejectException;
import com.jd.live.agent.core.extension.annotation.Extension;
import com.jd.live.agent.governance.instance.Endpoint;
import com.jd.live.agent.governance.invoke.InvocationContext;
import com.jd.live.agent.governance.invoke.OutboundInvocation;
import com.jd.live.agent.governance.policy.service.cluster.ClusterPolicy;
import com.jd.live.agent.governance.request.ServiceRequest.OutboundRequest;
import com.jd.live.agent.governance.response.ServiceResponse.OutboundResponse;

import java.util.List;
import java.util.function.Function;

/**
 * A {@code ClusterInvoker} that implements the fail-fast cluster invocation strategy.
 * <p>
 * When using the fail-fast strategy, the invoker will attempt to execute an invocation only once.
 * If the invocation fails due to a network error, a service error, or any other kind of
 * exceptional condition, it will not attempt to retry or reroute the invocation to another node.
 * Instead, it will fail immediately and return the exception.
 * </p>
 * <p>
 * This strategy is typically used when it is preferable to fail an operation and handle the
 * exception immediately rather than waiting for retries that may ultimately still fail. It reduces
 * the impact on system resources and operation time when a failure is likely unrecoverable.
 * </p>
 */
@Extension(value = ClusterInvoker.TYPE_FAILFAST, order = ClusterInvoker.ORDER_FAILFAST)
public class FailfastClusterInvoker implements ClusterInvoker {

    @SuppressWarnings("unchecked")
    @Override
    public <R extends OutboundRequest,
            O extends OutboundResponse,
            E extends Endpoint,
            T extends Throwable> O execute(LiveCluster<R, O, E, T> cluster,
                                           ClusterPolicy defaultPolicy,
                                           OutboundInvocation<R> invocation,
                                           Function<OutboundInvocation<R>, List<? extends Endpoint>> routing,
                                           InvocationContext context) {
        R request = invocation.getRequest();
        E endpoint = null;
        try {
            List<? extends Endpoint> instances = invocation.getInstances();
            instances = instances == null || instances.isEmpty() ? cluster.route(request) : instances;
            invocation.setInstances(instances);
            List<? extends Endpoint> endpoints = routing.apply(invocation);
            if (endpoints != null && !endpoints.isEmpty()) {
                endpoint = (E) endpoints.get(0);
                return cluster.invoke(request, endpoint);
            }
            return cluster.createResponse(cluster.createNoProviderException(request), request, null);
        } catch (RejectException e) {
            return cluster.createResponse(cluster.createRejectException(e), request, endpoint);
        } catch (Throwable e) {
            return cluster.createResponse(e, request, endpoint);
        }
    }
}
