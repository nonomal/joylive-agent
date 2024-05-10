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
package com.jd.live.agent.implement.flowcontrol.resilience4j.retry;

import com.jd.live.agent.core.extension.annotation.Extension;
import com.jd.live.agent.governance.invoke.retry.Retrier;
import com.jd.live.agent.governance.invoke.retry.RetrierFactory;
import com.jd.live.agent.governance.policy.service.cluster.RetryPolicy;

/**
 * Resilience4jRetrierFactory
 *
 * @since 1.0.0
 */
@Extension(value = "Resilience4j")
public class Resilience4jRetrierFactory implements RetrierFactory {

    @Override
    public Retrier get(RetryPolicy policy) {
        return policy == null ? null : new Resilience4jRetrier(policy);
    }
}
