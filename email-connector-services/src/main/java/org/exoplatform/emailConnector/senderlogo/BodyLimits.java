/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.senderlogo;

import java.util.function.Predicate;

/**
 * What one fetch of {@link SenderLogoFetcher} accepts as an answer (EXO-90909): an image
 * refused whole past its limit, or a home page cut at its own.
 *
 * @param declaredType the {@code Content-Type} values accepted
 * @param limit the most bytes read
 * @param keepPrefix whether a longer body is cut at the limit rather than refused
 * @param accept the {@code Accept} header sent
 * @param deadline the fetch's deadline, as {@link System#nanoTime()}
 */
record BodyLimits(Predicate<String> declaredType, int limit, boolean keepPrefix, String accept, long deadline) {
}
