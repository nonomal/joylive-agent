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
package com.jd.live.agent.plugin.router.springgateway.v4.filter;

import com.jd.live.agent.governance.invoke.InvocationContext;
import com.jd.live.agent.plugin.router.springgateway.v4.cluster.GatewayCluster;
import com.jd.live.agent.plugin.router.springgateway.v4.config.GatewayConfig;
import com.jd.live.agent.plugin.router.springgateway.v4.filter.LiveGatewayFilterChain.DefaultGatewayFilterChain;
import lombok.Getter;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory.RetryConfig;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.jd.live.agent.core.util.type.ClassUtils.getValue;
import static com.jd.live.agent.plugin.router.springgateway.v4.filter.LiveRouteFilter.ROUTE_VERSION;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/**
 * A utility class that holds the configuration for gateway filters.
 */
@Getter
public class LiveChainBuilder {

    public static final String SCHEMA_LB = "lb";
    private static final String TYPE_GATEWAY_FILTER_ADAPTER = "org.springframework.cloud.gateway.handler.FilteringWebHandler$GatewayFilterAdapter";
    private static final String TYPE_RETRY_FILTER = "org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory$1";
    private static final String TYPE_ROUTE_TO_REQUEST_URL_FILTER = "org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter";
    private static final String FIELD_CLIENT_FACTORY = "clientFactory";
    private static final String FIELD_RETRY_CONFIG = "val$retryConfig";
    private static final String FIELD_DELEGATE = "delegate";
    private static final String FIELD_GLOBAL_FILTERS = "globalFilters";
    private static final String SCHEME_REGEX = "[a-zA-Z]([a-zA-Z]|\\d|\\+|\\.|-)*:.*";
    private static final Pattern SCHEME_PATTERN = Pattern.compile(SCHEME_REGEX);
    private static final int WRITE_RESPONSE_FILTER_ORDER = -1;

    /**
     * The invocation context for this filter configuration.
     */
    private final InvocationContext context;

    /**
     * The gateway configuration for this filter configuration.
     */
    private final GatewayConfig gatewayConfig;

    /**
     * The target object for this filter configuration.
     */
    private final Object target;

    /**
     * The list of global filters for this filter configuration.
     */
    private final List<GatewayFilter> globalFilters;

    /**
     * The gateway cluster for this filter configuration.
     */
    private final GatewayCluster cluster;

    private ReactiveLoadBalancer.Factory<ServiceInstance> clientFactory;

    private final Map<Route, LiveRouteFilter> routeFilters = new ConcurrentHashMap<>();

    /**
     * Constructs a new FilterConfig instance with the specified parameters.
     *
     * @param context       the invocation context for this filter configuration
     * @param gatewayConfig the gateway configuration for this filter configuration
     * @param target        the target object for this filter configuration
     */
    public LiveChainBuilder(InvocationContext context, GatewayConfig gatewayConfig, Object target) {
        this.context = context;
        this.gatewayConfig = gatewayConfig;
        this.target = target;
        this.globalFilters = getGlobalFilters(target);
        this.cluster = new GatewayCluster(clientFactory);
    }

    /**
     * Creates a new GatewayFilterChain instance based on the current filter configuration.
     *
     * @param exchange the ServerWebExchange representing the incoming request
     * @return a new GatewayFilterChain instance
     */
    public GatewayFilterChain chain(ServerWebExchange exchange) {
        Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);
        long version = ROUTE_VERSION.get();
        // get filter from cache
        LiveRouteFilter routeFilter = routeFilters.computeIfAbsent(route, r -> createRouteFilter(r, version));
        if (routeFilter.getVersion() != version) {
            // route is changed. so remove from cache
            routeFilters.remove(route);
        }

        boolean loadbalancer = pareURI(exchange, route, routeFilter.getPathFilters());

        return new DefaultGatewayFilterChain(routeFilter.getFilters(), loadbalancer);
    }

    /**
     * Creates a new instance of LiveRouteFilter based on the given Route object and version.
     *
     * @param route   the Route object to create the LiveRouteFilter from
     * @param version the version of the LiveRouteFilter
     * @return a new instance of LiveRouteFilter
     */
    private LiveRouteFilter createRouteFilter(Route route, long version) {
        List<GatewayFilter> routeFilters = route.getFilters();
        List<GatewayFilter> pathFilters = new ArrayList<>(4);
        List<GatewayFilter> filters = globalFilters;
        RetryConfig retryConfig = null;
        if (!routeFilters.isEmpty()) {
            filters = new ArrayList<>(globalFilters);
            GatewayFilter delegate;
            for (GatewayFilter filter : routeFilters) {
                delegate = filter instanceof OrderedGatewayFilter ? ((OrderedGatewayFilter) filter).getDelegate() : null;
                if (delegate != null) {
                    String name = delegate.getClass().getName();
                    if (name.equals(TYPE_RETRY_FILTER)) {
                        // ignore retry
                        retryConfig = getValue(delegate, FIELD_RETRY_CONFIG);
                    } else if (gatewayConfig.isPathFilter(name)) {
                        // ignore path filter
                        pathFilters.add(filter);
                    } else {
                        filters.add(filter);
                    }
                } else {
                    filters.add(filter);
                }
            }
        }
        AnnotationAwareOrderComparator.sort(filters);
        AnnotationAwareOrderComparator.sort(pathFilters);

        // insert filters
        int pos = 0;
        int order;
        for (int i = 0; i < filters.size(); i++) {
            GatewayFilter gatewayFilter = filters.get(i);
            if (gatewayFilter instanceof Ordered) {
                order = ((Ordered) gatewayFilter).getOrder();
                if (order >= WRITE_RESPONSE_FILTER_ORDER) {
                    pos = i;
                    break;
                }
            }

        }
        LiveGatewayFilter liveFilter = new LiveGatewayFilter(context, gatewayConfig, cluster, retryConfig, pos);
        filters.add(pos, new OrderedGatewayFilter(liveFilter, WRITE_RESPONSE_FILTER_ORDER - 1));
        return new LiveRouteFilter(route, filters, pathFilters, version);
    }

    /**
     * Retrieves the list of global filters from the specified target object.
     *
     * @param target the target object
     * @return the list of global filters
     */
    private List<GatewayFilter> getGlobalFilters(Object target) {
        // this is sorted by order
        List<GatewayFilter> filters = getValue(target, FIELD_GLOBAL_FILTERS);
        List<GatewayFilter> result = new ArrayList<>(filters.size());
        GatewayFilter delegate;
        GlobalFilter globalFilter;
        // filter
        for (GatewayFilter filter : filters) {
            delegate = filter;
            if (filter instanceof OrderedGatewayFilter) {
                delegate = ((OrderedGatewayFilter) filter).getDelegate();
            }
            if (delegate.getClass().getName().equals(TYPE_GATEWAY_FILTER_ADAPTER)) {
                globalFilter = getValue(delegate, FIELD_DELEGATE);
                if (globalFilter instanceof ReactiveLoadBalancerClientFilter) {
                    clientFactory = getValue(globalFilter, FIELD_CLIENT_FACTORY);
                } else if (globalFilter == null || !globalFilter.getClass().getName().equals(TYPE_ROUTE_TO_REQUEST_URL_FILTER)) {
                    // the filter is implemented by parseURI
                    result.add(filter);
                }
            } else {
                result.add(filter);
            }
        }
        return result;
    }

    /**
     * Parses the URI and determines whether load balancing is used based on the given route information, the current ServerWebExchange object, and an optional list of GatewayFilters for rewriting the path.
     *
     * @param exchange    The current ServerWebExchange object, which contains information about the request and response.
     * @param route       The current route information, including the path, host, port, etc.
     * @param pathFilters An optional list of GatewayFilters used to rewrite the path.
     * @return A boolean indicating whether load balancing is used.
     */
    private boolean pareURI(ServerWebExchange exchange, Route route, List<GatewayFilter> pathFilters) {
        URI routeUri = route.getUri();

        String scheme = routeUri.getScheme();
        String schemePrefix = null;
        boolean hasAnotherScheme = routeUri.getHost() == null && routeUri.getRawPath() == null
                && SCHEME_PATTERN.matcher(routeUri.getSchemeSpecificPart()).matches();
        Map<String, Object> attributes = exchange.getAttributes();
        if (hasAnotherScheme) {
            schemePrefix = routeUri.getScheme();
            attributes.put(GATEWAY_SCHEME_PREFIX_ATTR, schemePrefix);
            routeUri = URI.create(routeUri.getSchemeSpecificPart());
            scheme = routeUri.getScheme();
        }

        if (pathFilters != null && !pathFilters.isEmpty()) {
            LiveGatewayFilterChain chain = new DefaultGatewayFilterChain(pathFilters);
            chain.filter(exchange).subscribe();
        }
        URI uri = exchange.getAttributeOrDefault(GATEWAY_REQUEST_URL_ATTR, exchange.getRequest().getURI());
        boolean encoded = containsEncodedParts(uri);
        uri = UriComponentsBuilder.fromUri(uri)
                .scheme(routeUri.getScheme())
                .host(routeUri.getHost())
                .port(routeUri.getPort())
                .build(encoded)
                .toUri();
        attributes.put(GATEWAY_REQUEST_URL_ATTR, uri);
        return SCHEMA_LB.equals(scheme) || SCHEMA_LB.equals(schemePrefix);
    }
}