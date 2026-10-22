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
package org.exoplatform.emailConnector.service;

import org.exoplatform.commons.exception.ObjectNotFoundException;

/**
 * Work done on a draft while its lock is held (see
 * {@code EmailBoxService.inDraftLock}), EXO-91996.
 *
 * @param <T> what the work answers
 */
@FunctionalInterface
public interface DraftLockedAction<T> {

  /**
   * Does the work.
   *
   * @return what it answers
   * @throws IllegalAccessException if the user may not use their mailbox
   * @throws ObjectNotFoundException when what it acts on is not there
   */
  T run() throws IllegalAccessException, ObjectNotFoundException;
}
