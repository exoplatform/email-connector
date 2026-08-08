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
package org.exoplatform.emailConnector.model;

import org.apache.commons.lang3.StringUtils;

/**
 * A contact's postal address, kept STRUCTURED — one field per vCard {@code ADR}
 * component — rather than as one formatted string.
 * <p>
 * The choice is deliberate, and it is the opposite of the one the store made
 * for phones. A joined string is cheap to hold but expensive the day anything
 * needs the parts back: the phone string is exactly what made phone matching
 * unreliable, because the parts could no longer be told apart. An address is
 * worse off flattened — the export must write {@code ADR}'s street, locality,
 * region, code and country back into their own slots, and a flattened address
 * can only ever leave as one long "street" line, which every importer then
 * displays wrong. Structure costs five nullable columns once; flattening costs
 * every future reader a parser that cannot be written correctly.
 *
 * @param street the street line — the vCard street component, with the PO box
 *          and extended-address components folded in front of it when a card
 *          carries them (three slots for what is one visual line is more
 *          structure than this store has any use for)
 * @param city the locality
 * @param region the region, state or province
 * @param postalCode the postal code
 * @param country the country, as the card spells it — never a resolved code
 */
public record PostalAddress(String street,
                            String city,
                            String region,
                            String postalCode,
                            String country) {

  /**
   * The address these components make, or null when every one of them is blank
   * — the one factory every mapping site uses, so "no address" is always null
   * and never an empty shell the card would render as blank rows.
   *
   * @param street the street line
   * @param city the locality
   * @param region the region, state or province
   * @param postalCode the postal code
   * @param country the country
   * @return the trimmed address, or null when there is nothing in it
   */
  public static PostalAddress orNull(String street, String city, String region, String postalCode, String country) {
    String trimmedStreet = StringUtils.trimToNull(street);
    String trimmedCity = StringUtils.trimToNull(city);
    String trimmedRegion = StringUtils.trimToNull(region);
    String trimmedPostalCode = StringUtils.trimToNull(postalCode);
    String trimmedCountry = StringUtils.trimToNull(country);
    if (trimmedStreet == null && trimmedCity == null && trimmedRegion == null && trimmedPostalCode == null
        && trimmedCountry == null) {
      return null;
    }
    return new PostalAddress(trimmedStreet, trimmedCity, trimmedRegion, trimmedPostalCode, trimmedCountry);
  }

  /**
   * The address as one line — what the MCP contact card answers, where an agent
   * wants something to say rather than components to assemble.
   *
   * @return the non-blank components joined with commas
   */
  public String toSingleLine() {
    return java.util.stream.Stream.of(street, city, region, postalCode, country)
                                  .filter(StringUtils::isNotBlank)
                                  .reduce((a, b) -> a + ", " + b)
                                  .orElse("");
  }
}
