/**
 * Copyright (C) 2025 eXo Platform SAS
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
package org.exoplatform.emailConnector.utils;

public class NotificationConstants {

  public static final String CONTEXT    = "CONTEXT";

  public static final String NEW_EMAILS = "NEW_EMAILS";

  public static final String RECEIVER   = "RECEIVER";

  public enum NOTIFICATION_CONTEXT {
    NEW_EMAILS_RECIEVED("NEW EMAILS RECIEVED");

    private String context;

    private NOTIFICATION_CONTEXT(String context) {
      this.context = context;
    }

    public String getContext() {
      return this.context;
    }
  }
}
