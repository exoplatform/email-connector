/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.model;

import java.util.List;

/**
 * Mails of an owner's inbox that just got a category: the data of
 * {@code EmailConnectorUtils.EMAIL_CATEGORY_ADDED}, whose source is the owner.
 *
 * @param categoryId the category added
 * @param mailRemoteIds the INBOX UIDs of the mails whose link stuck
 */
public record EmailCategoryAdded(long categoryId, List<Long> mailRemoteIds) {
}
