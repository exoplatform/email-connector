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

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converts a {@code List<String>} entity attribute to/from a semicolon-joined
 * VARCHAR column, the same delimiter the EMAIL_CONTACT EMAILS/PHONES columns
 * have always stored (unlike {@code org.exoplatform.commons.utils.StringListConverter},
 * which is comma-delimited and would corrupt EMAILS' per-entry "type,value" pairs).
 */
@Converter
public class SemicolonListConverter implements AttributeConverter<List<String>, String> {

  private static final String DELIMITER = ";";

  @Override
  public String convertToDatabaseColumn(List<String> attribute) {
    if (attribute == null || attribute.isEmpty()) {
      return null;
    }
    String joined = String.join(DELIMITER, attribute);
    return StringUtils.isBlank(joined) ? null : joined;
  }

  @Override
  public List<String> convertToEntityAttribute(String dbData) {
    if (StringUtils.isBlank(dbData)) {
      return null;
    }
    return Arrays.stream(dbData.split(DELIMITER)).toList();
  }
}
