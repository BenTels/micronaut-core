/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.inject.method.iterableinjection

import io.micronaut.context.BeanContext
import io.micronaut.context.DefaultBeanContext
import spock.lang.Issue
import spock.lang.Specification

@Issue("https://github.com/micronaut-projects/micronaut-core/issues/5187")
class SetterIterableInjectionSpec extends Specification {

    void "test Iterable injection via setter - method is private"() {
        given:
        BeanContext context = new DefaultBeanContext()
        context.start()

        when:
        def instance = context.getBean(InjectMethodIsPrivate)

        then:
        instance.all != null
        instance.all.size() == 2
        instance.all.contains(context.getBean(AImpl))
        instance.all.contains(context.getBean(AnotherImpl))
    }

    void "test Iterable injection via setter - method is not private"() {
        given:
        BeanContext context = new DefaultBeanContext()
        context.start()

        when:
        def instance = context.getBean(InjectMethodNotPrivate)

        then:
        instance.all != null
        instance.all.size() == 2
        instance.all.contains(context.getBean(AImpl))
        instance.all.contains(context.getBean(AnotherImpl))
    }

}