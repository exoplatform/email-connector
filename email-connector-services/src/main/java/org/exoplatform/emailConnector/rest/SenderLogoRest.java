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
package org.exoplatform.emailConnector.rest;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.service.SenderLogoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Serves the senders' brand logos (EXO-90893), fetched and cleaned by the server, so
 * that the browser never contacts the brand.
 * <p>
 * <b>Who may ask.</b> Any authenticated user, for any domain. A logo is a brand's
 * public image, the same for every reader, so serving it reveals nothing about anyone's
 * mail. The endpoint serves the server's cache and nothing else: it never fetches, so
 * no caller can make the server reach a domain of its choosing; only a message that
 * passed DMARC, opened by its recipient, has a logo resolved
 * ({@code SenderLogoService#logoUrlFor}).
 * <p>
 * <b>How it is served.</b> As the type read from the image's own bytes (an SVG only
 * once sanitised), never sniffed again by the browser ({@code nosniff}), with a
 * Content-Security-Policy that forbids scripts, external loads and plugins and
 * sandboxes the document, should a browser ever open the logo as a page rather than as
 * an image, and kept by the browser for a day.
 */
@RestController
@RequestMapping("/email-box/sender-logo")
public class SenderLogoRest {

  /** No script, no external load, no plugin, no form: drawing only, sandboxed. */
  static final String       CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; sandbox";

  private static final String NO_SNIFF_HEADER       = "X-Content-Type-Options";

  @Autowired
  private SenderLogoService senderLogoService;

  /**
   * A domain's brand logo.
   *
   * @param domain the domain, as the URL offered by the reader names it
   * @return the image
   */
  @GetMapping("/{domain:.+}")
  @Secured("users")
  @Operation(summary = "Gets the brand logo of a mail domain", method = "GET", description = "This returns the logo the domain publishes (BIMI), else its site's icon, as the server fetched, checked and cached it; it never fetches")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "Not a domain name"),
      @ApiResponse(responseCode = "404", description = "No logo cached for the domain, or brand logos are switched off") })
  public ResponseEntity<byte[]> getSenderLogo(@Parameter(description = "The mail domain", required = true)
                                              @PathVariable("domain")
                                              String domain) {
    SenderLogo logo;
    try {
      logo = senderLogoService.getLogo(domain);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    if (logo == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.ok()
                         .contentType(MediaType.parseMediaType(logo.getContentType()))
                         .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                         .header(NO_SNIFF_HEADER, "nosniff")
                         .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                         .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                         .body(logo.getData());
  }
}
