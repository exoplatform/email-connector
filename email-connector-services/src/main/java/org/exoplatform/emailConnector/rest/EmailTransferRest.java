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

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.model.ExportCheck;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.service.EmailExportService;
import org.exoplatform.emailConnector.service.RawEmailSink;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Mail out of the mailbox as files: the selection as a {@code .zip} of {@code .eml}
 * and a folder as an {@code .mbox} (EXO-90845). Who may read is the services' to decide;
 * this layer keeps each answer's status, and sends the files as downloads a browser
 * never renders.
 */
@RestController
@RequestMapping("/email-box")
@Tag(name = "/email-connector/rest/email-box", description = "Exports mail as files")
public class EmailTransferRest {

  // What a zip export is served as.
  private static final String ZIP_CONTENT_TYPE  = "application/zip";

  // What an mbox export is served as (RFC 4155).
  private static final String MBOX_CONTENT_TYPE = "application/mbox";

  // The zip export's file name.
  private static final String ZIP_FILE_NAME     = "emails.zip";

  // Keeps a browser from sniffing the senders' bytes into anything but a download.
  private static final String NO_SNIFF_HEADER   = "X-Content-Type-Options";

  @Autowired
  private EmailExportService  emailExportService;

  /**
   * Checks a zip export before its download starts: how many messages, and the most a
   * zip holds. Every message is checked as the download would check it.
   *
   * @param request the caller's request, for the acting user
   * @param mails the selection keys, {@code <folder>:<uid>}
   * @return the check
   */
  @GetMapping("/export/zip/check")
  @Secured("users")
  @Operation(summary = "Checks a .zip export of selected emails", method = "GET",
      description = "Answers how many messages the .zip would hold and the most one .zip holds, after checking every message as the "
          + "download would: each must be in the caller's own cache under the folder named, and in a shared folder the caller must "
          + "hold the read right.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "An empty or malformed selection"),
      @ApiResponse(responseCode = "403", description = "Forbidden: the caller may not read their mailbox, or lacks the read right on a shared folder"),
      @ApiResponse(responseCode = "404", description = "A message is not in the caller's mailbox"),
      @ApiResponse(responseCode = "410", description = "The mailbox share a folder belongs to has ended"), })
  public ExportCheck checkZipExport(HttpServletRequest request,
                                    @Parameter(description = "The selected messages, <folder>:<uid>", required = true)
                                    @RequestParam("mails")
                                    List<String> mails) {
    try {
      ExportCheck check = emailExportService.checkZip(request.getRemoteUser(), mails);
      if (check == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return check;
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxRightMissingException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (DelegationRevokedException e) {
      throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }

  /**
   * Downloads selected emails as a {@code .zip}, one {@code .eml} per message, streamed
   * as the mail server hands them over. Nothing is written before every message was
   * checked, so a refusal keeps its own status.
   *
   * @param request the caller's request, for the acting user
   * @param response where the zip is streamed
   * @param mails the selection keys, {@code <folder>:<uid>}
   */
  @GetMapping("/export/zip")
  @Secured("users")
  @Operation(summary = "Downloads selected emails as a .zip of .eml files", method = "GET",
      description = "Streams one .eml per message, named after its subject and date, never marking a message read. One message the "
          + "caller may not read, or does not have, refuses the whole download. A message the mail server no longer holds is named in a "
          + "not-exported.txt entry.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "An empty or malformed selection, or more messages than one .zip holds"),
      @ApiResponse(responseCode = "403", description = "Forbidden: the caller may not read their mailbox, or lacks the read right on a shared folder"),
      @ApiResponse(responseCode = "404", description = "A message is not in the caller's mailbox"),
      @ApiResponse(responseCode = "410", description = "The mailbox share a folder belongs to has ended"),
      @ApiResponse(responseCode = "500", description = "The mail server could not be read"), })
  public void downloadZip(HttpServletRequest request,
                          HttpServletResponse response,
                          @Parameter(description = "The selected messages, <folder>:<uid>", required = true)
                          @RequestParam("mails")
                          List<String> mails) {
    try {
      boolean found = emailExportService.writeZip(request.getRemoteUser(), mails, download(response, ZIP_CONTENT_TYPE, ZIP_FILE_NAME));
      if (!found) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxRightMissingException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (DelegationRevokedException e) {
      throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Checks a folder's {@code .mbox} export before its download starts: how many
   * messages the folder holds, and the most an {@code .mbox} holds.
   *
   * @param request the caller's request, for the acting user
   * @param folder the folder key; INBOX when omitted
   * @return the check
   */
  @GetMapping("/export/mbox/check")
  @Secured("users")
  @Operation(summary = "Checks the .mbox export of a folder", method = "GET",
      description = "Answers how many messages the folder holds on the mail server and the most one .mbox holds. Only a folder of the "
          + "caller's mailbox, or of a mailbox shared with them where they hold the read right on that folder.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "403", description = "Forbidden: the caller may not read their mailbox, or lacks the read right on that shared folder"),
      @ApiResponse(responseCode = "404", description = "The caller has no such folder"),
      @ApiResponse(responseCode = "410", description = "The mailbox share that folder belongs to has ended"),
      @ApiResponse(responseCode = "500", description = "The mail server could not be read"), })
  public ExportCheck checkMboxExport(HttpServletRequest request,
                                     @Parameter(description = "The folder key; INBOX when omitted")
                                     @RequestParam(value = "folder", required = false, defaultValue = MailFolder.INBOX)
                                     String folder) {
    try {
      ExportCheck check = emailExportService.checkMbox(request.getRemoteUser(), folder);
      if (check == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
      return check;
    } catch (MailboxRightMissingException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (DelegationRevokedException e) {
      throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * Downloads a whole folder as one {@code .mbox} (mboxrd), streamed as the mail server
   * hands its messages over, oldest first, never marking a message read.
   *
   * @param request the caller's request, for the acting user
   * @param response where the file is streamed
   * @param folder the folder key; INBOX when omitted
   */
  @GetMapping("/export/mbox")
  @Secured("users")
  @Operation(summary = "Downloads a folder as an .mbox file", method = "GET",
      description = "Streams every message of the folder as the mail server holds it, in the mboxrd format, never marking a message "
          + "read. Only a folder of the caller's mailbox, or of a mailbox shared with them where they hold the read right on that folder.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Request fulfilled"),
      @ApiResponse(responseCode = "400", description = "The folder holds more messages than one .mbox holds"),
      @ApiResponse(responseCode = "403", description = "Forbidden: the caller may not read their mailbox, or lacks the read right on that shared folder"),
      @ApiResponse(responseCode = "404", description = "The caller has no such folder"),
      @ApiResponse(responseCode = "410", description = "The mailbox share that folder belongs to has ended"),
      @ApiResponse(responseCode = "500", description = "The mail server could not be read"), })
  public void downloadMbox(HttpServletRequest request,
                           HttpServletResponse response,
                           @Parameter(description = "The folder key; INBOX when omitted")
                           @RequestParam(value = "folder", required = false, defaultValue = MailFolder.INBOX)
                           String folder) {
    try {
      RawEmailSink sink = (folderName, size) -> download(response,
                                                         MBOX_CONTENT_TYPE,
                                                         EmailConnectorUtils.safeFileName(folderName, "mailbox", ".mbox")).open(null,
                                                                                                                               size);
      boolean found = emailExportService.writeMbox(request.getRemoteUser(), folder, sink);
      if (!found) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
      }
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (MailboxRightMissingException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (DelegationRevokedException e) {
      throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  /**
   * The sink of a download: once the service has checked everything, the response gets
   * its content type, an attachment disposition with a safe file name, no caching and no
   * sniffing, and its stream is handed over.
   *
   * @param response the response
   * @param contentType the file's media type
   * @param fileName the file's name, already safe
   * @return the sink
   */
  private static RawEmailSink download(HttpServletResponse response, String contentType, String fileName) {
    return (name, size) -> {
      response.setContentType(contentType);
      response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                         ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString());
      response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().cachePrivate().getHeaderValue());
      response.setHeader(NO_SNIFF_HEADER, "nosniff");
      return response.getOutputStream();
    };
  }
}
