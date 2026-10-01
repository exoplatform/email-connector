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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.model.CalendarInvitation;
import org.exoplatform.emailConnector.rest.model.InvitationReplyRequest;
import org.exoplatform.emailConnector.service.CalendarInvitationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The calendar invitation a mail carries (EXO-90840): the event, and the attendee's
 * answer. A message is addressed by its technical id, as the read receipt's answer is.
 */
@RestController
@RequestMapping("/email-box")
@Tag(name = "/email-connector/rest/email-box", description = "Manages Email Box")
public class CalendarInvitationRest {

  /** The invitations' service. */
  @Autowired
  private CalendarInvitationService calendarInvitationService;

  /**
   * The invitation a message of the caller carries.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param emailId the message's technical id
   * @return the invitation
   */
  @GetMapping("/{emailId}/invitation")
  @Secured("users")
  @Operation(summary = "Gets the calendar invitation a message carries", method = "GET",
             description = "The event a message's iCalendar part (text/calendar, application/ics or a .ics file) describes, read from the caller's mail server and capped in size: method, UID, sequence, title, place, start and end instants (or the days of an all-day event), the time zone it was set in, its recurrence in words when it can be said, the organiser, the attendees (the first 50, and how many there are), the address the caller answers for (theirs, or a shared mailbox owner's), the caller's current answer, whether Accept / Maybe / Decline may be offered, and why not when a shared mailbox's owner does not let the caller send in her name. Every text is the sender's, to be shown as text.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "The invitation"),
      @ApiResponse(responseCode = "400", description = "The invitation is too large (emailConnector.invitation.tooLarge), cannot be read (emailConnector.invitation.unreadable), or the server cannot read invitations (emailConnector.invitation.unsupported)"),
      @ApiResponse(responseCode = "403", description = "The caller's mailbox connector is not usable"),
      @ApiResponse(responseCode = "404", description = "No such message of the caller's, or it carries no invitation (emailConnector.invitation.notFound)"),
      @ApiResponse(responseCode = "500", description = "The mail server could not be read"), })
  public CalendarInvitation getInvitation(HttpServletRequest request,
                                          @Parameter(description = "Technical id of the message", required = true)
                                          @PathVariable("emailId")
                                          long emailId) {
    try {
      return calendarInvitationService.getInvitation(emailId, request.getRemoteUser());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, CalendarInvitationService.NOT_FOUND);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalAccessException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  /**
   * Answers the invitation a message of the caller carries.
   *
   * @param request the HTTP request, carrying the authenticated user
   * @param emailId the message's technical id
   * @param reply the answer
   * @return the invitation, with the answer given
   */
  @PostMapping("/{emailId}/invitation/reply")
  @Secured("users")
  @Operation(summary = "Answers the calendar invitation a message carries", method = "POST",
             description = "Sends the attendee's iTIP REPLY (RFC 5546, over iMIP RFC 6047) to the invitation's organiser: METHOD:REPLY with the invitation's UID, SEQUENCE and RECURRENCE-ID, and the attendee with PARTSTAT ACCEPTED, TENTATIVE or DECLINED. It goes out over the caller's own connector, with no copy in Sent, from the caller's address -- or, for a message of a mailbox shared with the caller, in its owner's name, on her behalf when she allows it (SENT-BY names the caller) and as her otherwise. The answer is remembered for the event and its sequence, and may be changed by answering again. Only a REQUEST that is not cancelled, received (not in Sent, Drafts, Junk or Trash), whose organiser is one mail address other than the caller's, can be answered; everything is checked again here.")
  @ApiResponses(value = { @ApiResponse(responseCode = "200", description = "Answered; the invitation with the answer given"),
      @ApiResponse(responseCode = "400", description = "No valid answer (emailConnector.invitation.invalidAnswer), nothing to answer from here (emailConnector.invitation.notAnswerable), the event was cancelled (emailConnector.invitation.cancelled), the invitation cannot be read (tooLarge, unreadable, unsupported), or the owner's mail server refused a mail in her name (emailConnector.sendMode.refusedByServer)"),
      @ApiResponse(responseCode = "403", description = "The caller's mailbox connector is not usable, or the shared mailbox's owner does not let the caller send in her name (emailConnector.invitation.sendNotAllowed)"),
      @ApiResponse(responseCode = "404", description = "No such message of the caller's, or it carries no invitation"),
      @ApiResponse(responseCode = "500", description = "The answer could not be sent (emailConnector.invitation.sendFailed), or the mail server failed after it may have been accepted (emailConnector.invitation.unconfirmed)"), })
  public CalendarInvitation respond(HttpServletRequest request,
                                    @Parameter(description = "Technical id of the message", required = true)
                                    @PathVariable("emailId")
                                    long emailId,
                                    @Parameter(description = "The answer: ACCEPTED, TENTATIVE or DECLINED", required = true)
                                    @RequestBody
                                    InvitationReplyRequest reply) {
    try {
      return calendarInvitationService.respond(emailId, request.getRemoteUser(), reply == null ? null : reply.getAnswer());
    } catch (ObjectNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, CalendarInvitationService.NOT_FOUND);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalAccessException e) {
      // The code only when it is the shared mailbox's refusal: the connector's own
      // refusal carries a sentence naming the user, which is no response body.
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                        CalendarInvitationService.SEND_NOT_ALLOWED.equals(e.getMessage()) ? e.getMessage()
                                                                                                         : null);
    } catch (IllegalStateException e) {
      String code = e.getMessage();
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        CalendarInvitationService.UNCONFIRMED.equals(code) ? code
                                                                                         : CalendarInvitationService.SEND_FAILED);
    }
  }
}
