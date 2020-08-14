/*
 * Copyright 2020 Matthew Weis, Kansas State University
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

/**
 * Provides classes necessary for this instrumented Reactor distribution to provide virtual-time capabilities.
 * <br>
 * There are two classes intended for direct access by the user: {@link org.sireum.hooks.TimeBarriers} (the core api)
 * and {@link org.sireum.hooks.TimeUtils} (utility functions supporting the core api). The third public class,
 * {@link org.sireum.hooks.ReactorAdvice} exists only to support the instrumented Reactor distribution, and
 * should NOT be accessed by users. All other classes are either internal or public exceptions.
 */
package org.sireum.hooks;