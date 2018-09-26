/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal;

import org.gradle.api.Action;
import org.gradle.internal.Factory;

public class DefaultMutationGuard extends AbstractMutationGuard {
    private ThreadLocal<Boolean> isMutationAllowed = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.TRUE;
        }
    };
    private ThreadLocal<Object> mutatingSubject = new ThreadLocal<Object>() {
        @Override
        protected Object initialValue() {
            return null;
        }
    };

    @Override
    public boolean isMutationAllowed() {
        return isMutationAllowed.get();
    }

    @Override
    public <T> boolean isSubjectMutationAllowed(T subject) {
        Object o = mutatingSubject.get();
        return o.equals(subject);
    }

    private <T> T getAndSetMutatingSubject(T newSubject) {
        T result = (T)mutatingSubject.get();
        mutatingSubject.set(newSubject);
        return result;
    }

    private boolean getAndSetMutationAllowed(boolean newMutationAllowed) {
        boolean result = isMutationAllowed.get();
        isMutationAllowed.set(newMutationAllowed);
        return result;
    }

    protected <T> Action<? super T> newActionWithMutation(final Action<? super T> action, final boolean allowMutationMethods) {
        return new Action<T>() {
            @Override
            public void execute(T t) {
                boolean oldIsMutationAllowed = getAndSetMutationAllowed(allowMutationMethods);
                T oldMutatingSubject = getAndSetMutatingSubject(t);
                try {
                    action.execute(t);
                } finally {
                    getAndSetMutatingSubject(oldMutatingSubject);
                    getAndSetMutationAllowed(oldIsMutationAllowed);
                }
            }
        };
    }

    protected void runWithMutation(final Runnable runnable, boolean allowMutationMethods) {
        boolean oldIsMutationAllowed = getAndSetMutationAllowed(allowMutationMethods);
        try {
            runnable.run();
        } finally {
            getAndSetMutationAllowed(oldIsMutationAllowed);
        }
    }

    protected <I> I createWithMutation(final Factory<I> factory, boolean allowMutationMethods) {
        boolean oldIsMutationAllowed = getAndSetMutationAllowed(allowMutationMethods);
        try {
            return factory.create();
        } finally {
            getAndSetMutationAllowed(oldIsMutationAllowed);
        }
    }
}
