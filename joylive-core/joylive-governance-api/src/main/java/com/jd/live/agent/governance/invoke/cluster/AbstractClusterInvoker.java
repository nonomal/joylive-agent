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
import com.jd.live.agent.core.instance.AppStatus;
import com.jd.live.agent.governance.instance.Endpoint;
import com.jd.live.agent.governance.invoke.InvocationContext;
import com.jd.live.agent.governance.invoke.OutboundInvocation;
import com.jd.live.agent.governance.policy.service.cluster.ClusterPolicy;
import com.jd.live.agent.governance.request.ServiceRequest.OutboundRequest;
import com.jd.live.agent.governance.response.ServiceResponse.OutboundResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;

/**
 * Abstract implementation of {@link ClusterInvoker} that manages the invocation of services
 * across a cluster of endpoints. This class provides a common framework for routing requests,
 * handling failures, and integrating with different cluster strategies.
 * <p>
 * Implementations must define how exceptions are handled by overriding the
 * {@link #onException(Throwable, OutboundRequest, Endpoint, LiveCluster, OutboundInvocation, CompletableFuture)}
 * method.
 */
public abstract class AbstractClusterInvoker implements ClusterInvoker {

    @Override
    public <R extends OutboundRequest,
            O extends OutboundResponse,
            E extends Endpoint,
            T extends Throwable> CompletionStage<O> execute(LiveCluster<R, O, E, T> cluster,
                                                            InvocationContext context,
                                                            OutboundInvocation<R> invocation,
                                                            ClusterPolicy defaultPolicy) {
        cluster.onStart(invocation.getRequest());
        return invoke(cluster, context, invocation, null);
    }

    /**
     * Invokes a service method on a cluster of endpoints. This method handles the routing of the
     * request to the appropriate endpoint(s) based on the cluster strategy and an optional predicate
     * that can further refine the selection of endpoints or influence the routing decision.
     *
     * @param <R>        The type of the outbound request that extends {@link OutboundRequest}.
     * @param <O>        The type of the outbound response that extends {@link OutboundResponse}.
     * @param <E>        The type of the endpoint that extends {@link Endpoint}.
     * @param <T>        The type of the throwable that extends {@link Throwable}.
     * @param cluster    The {@link LiveCluster} managing the distribution and processing of the request.
     * @param context    The {@link InvocationContext} providing additional information and context for the invocation.
     * @param invocation The {@link OutboundInvocation} representing the specific request and its routing information.
     * @param predicate  An optional {@link Predicate} that applies additional filtering or conditions on the request
     *                   before routing. If the predicate evaluates to true or is null, the cluster is queried for routing.
     *                   If the predicate is provided and evaluates to false, and specific instances are provided,
     *                   those instances are used for the invocation.
     * @return A {@link CompletionStage} that completes with the result of the service invocation.
     * This future may complete exceptionally if the invocation fails or if no suitable endpoints
     * can be found.
     */
    @SuppressWarnings("unchecked")
    protected <R extends OutboundRequest,
            O extends OutboundResponse,
            E extends Endpoint,
            T extends Throwable> CompletionStage<O> invoke(LiveCluster<R, O, E, T> cluster,
                                                           InvocationContext context,
                                                           OutboundInvocation<R> invocation,
                                                           Predicate<R> predicate) {
        CompletableFuture<O> result = new CompletableFuture<>();
        AppStatus appStatus = context.getAppStatus();
        if (!appStatus.outbound()) {
            T exception = cluster.createUnReadyException(appStatus.getMessage(), invocation.getRequest());
            if (exception != null) {
                result.completeExceptionally(exception);
                return result;
            }
        }
        R request = invocation.getRequest();
        List<? extends Endpoint> instances = invocation.getInstances();
        CompletionStage<List<E>> stage = instances == null || instances.isEmpty() || predicate != null && predicate.test(request)
                ? cluster.route(request)
                : CompletableFuture.completedFuture((List<E>) instances);
        stage.whenComplete((v, t) -> {
            if (t == null) {
                E endpoint = null;
                try {
                    endpoint = context.route(invocation, v);
                    invocation.onForward(endpoint, request);
                    E instance = endpoint;
                    cluster.onStartRequest(request, instance);
                    cluster.invoke(request, instance).whenComplete((o, r) -> {
                        if (r != null) {
                            onException(r, request, instance, cluster, invocation, result);
                        } else if (o.getThrowable() != null) {
                            onException(o.getThrowable(), request, instance, cluster, invocation, result);
                        } else {
                            onSuccess(cluster, invocation, o, request, instance, result);
                        }
                    });
                } catch (RejectException e) {
                    onException(cluster.createRejectException(e, request), request, endpoint, cluster, invocation, result);
                } catch (Throwable e) {
                    onException(e, request, endpoint, cluster, invocation, result);
                }
            } else {
                onException(t, request, null, cluster, invocation, result);
            }
        });
        return result;
    }

    /**
     * Handles a successful request response.
     *
     * @param <R>        the type of the outbound request
     * @param <O>        the type of the outbound response
     * @param <E>        the type of the endpoint
     * @param <T>        the type of the throwable
     * @param cluster    the live cluster instance representing the current active cluster
     * @param invocation the outbound invocation instance representing the outbound call
     * @param response   the instance representing the outbound response
     * @param request    the instance representing the outbound request
     * @param endpoint   the endpoint instance representing the endpoint
     * @param result     the CompletableFuture instance representing the result of an asynchronous computation
     */
    protected <R extends OutboundRequest,
            O extends OutboundResponse,
            E extends Endpoint,
            T extends Throwable> void onSuccess(LiveCluster<R, O, E, T> cluster,
                                                OutboundInvocation<R> invocation,
                                                O response,
                                                R request,
                                                E endpoint,
                                                CompletableFuture<O> result) {
        invocation.onSuccess(endpoint, request, response);
        cluster.onSuccess(response, request, endpoint);
        result.complete(response);
    }

    /**
     * Handles exceptions that occur during the invocation process. Subclasses must implement this
     * method to define custom exception handling logic, which may include logging, retrying the
     * invocation, or failing the request.
     *
     * @param <R>        The type of the outbound request that extends {@link OutboundRequest}.
     * @param <O>        The type of the outbound response that extends {@link OutboundResponse}.
     * @param <E>        The type of the endpoint that extends {@link Endpoint}.
     * @param <T>        The type of the throwable that extends {@link Throwable}.
     * @param throwable  The exception that occurred during the invocation.
     * @param request    The request that was being processed when the exception occurred.
     * @param endpoint   The endpoint to which the request was being sent, may be null if the exception
     *                   occurred before an endpoint was selected.
     * @param cluster    The live cluster context in which the invocation was taking place.
     * @param invocation The {@link OutboundInvocation} representing the specific request and its routing information.
     * @param result     The {@link CompletableFuture} that should be completed to signal the outcome of the
     *                   invocation. Implementers can complete this future exceptionally or with a default
     *                   response.
     */
    protected <R extends OutboundRequest,
            O extends OutboundResponse,
            E extends Endpoint,
            T extends Throwable> void onException(Throwable throwable,
                                                  R request,
                                                  E endpoint,
                                                  LiveCluster<R, O, E, T> cluster,
                                                  OutboundInvocation<R> invocation,
                                                  CompletableFuture<O> result) {
        invocation.onFailure(endpoint, request, throwable);
        O response = cluster.createResponse(throwable, request, endpoint);
        // avoid the live exception class is not recognized in application classloader
        cluster.onError(response.getThrowable(), request, endpoint);
        result.complete(response);
    }
}
