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
/**
 * The mailbox ACL engines behind mailbox delegation: the SPI
 * ({@link org.exoplatform.emailConnector.service.acl.MailboxAclEngine}), the caller's
 * session it is handed ({@link org.exoplatform.emailConnector.service.acl.MailboxAclSession}),
 * the registry that picks one per connector preset, and two implementations -- RFC
 * 4314 over IMAP, and none.
 * <p>
 * <b>The engine that is not here yet, and what it will implement.</b> A
 * {@code BlueMindAclEngine} is <b>mandatory</b> on BlueMind, not an optimisation: phase
 * 0 of the delegation plan (section 13.B.8) found a proxy in front of BlueMind's
 * mailstore that answers {@code * ALERT Missing analyzer} and drops the connection on
 * {@code SETACL} and {@code LISTRIGHTS}, exactly as it does on a nonsense command,
 * while {@code MYRIGHTS} and {@code GETACL} pass. {@code ImapAclEngine} can therefore
 * read on BlueMind and can never write there; its first grant would fail with a
 * dropped connection, not a {@code NO}. The BlueMind engine is a <b>hybrid</b> (plan,
 * section 3.2): reads over IMAP where they work and are cheaper, writes over the REST
 * API. It is deliberately not written in this delivery -- the SPI is shaped so that it
 * drops in without an interface change. Divergence by divergence, the affordance that
 * covers it:
 * <ul>
 * <li><b>Verbs, not letters</b> (plan, sections 3.4, 13.B.5). BlueMind's
 * {@code IMailboxes GET/POST {domainUid}/{mailboxUid}/_acls} rows carry
 * {@code Read, Write, SendAs, SendOnBehalf, Freebusy, Invitation, Visible, Manage,
 * ReadExtended, All}. The engine translates lossily both ways and keeps the original in
 * {@link org.exoplatform.emailConnector.model.MailboxAce#nativeRights()}, which the
 * lifecycle stores in {@code EMAIL_DELEGATION.NATIVE_RIGHTS} (changeset 1.0.0-80) for
 * display; {@code RIGHTS} stays letters. A share made in BlueMind's own interface with
 * verbs no letter expresses renders as {@code CUSTOM} with the verbs shown raw, never
 * collapsed.</li>
 * <li><b>The read preset is {@code lrp}, no {@code s}</b> (plan, section 3.4, from
 * BlueMind's {@code Acl.java} {@code RO = lrp}, consistent with the observed read-only
 * delegate who could not persist read state). {@code presetOf} is overridden so
 * {@code lrp} reads as READER and {@code lrswipkxte} ({@code RW}) as EDITOR; the
 * default exact match would label every BlueMind-made read share CUSTOM. On the REST
 * side the mapping is by verb set -- {@code {Read}} Reader, {@code {Write}} Editor --
 * with one open question the engine must settle against live rows: BlueMind's own read
 * share carried {@code Freebusy} and {@code Invitation} beside {@code Read}
 * (section 13.B.15), so the Reader match may need to tolerate those two.</li>
 * <li><b>Per mailbox, not per folder</b>. {@code _acls} is on the mailbox;
 * {@code probe} answers {@link org.exoplatform.emailConnector.model.GrantGranularity#MAILBOX}
 * and {@code grant} ignores the folder argument beyond validation. The allowlist
 * caveat of the plan (section 3.4, 8) applies: the {@code Write} verb pushes
 * {@code k x e} along with {@code lrswit}, so "never {@code x e k}" holds on IMAP
 * engines only and the guarantee here is "never more than the preset's verb";
 * {@code grant} refuses unless the owner's letters cover what the verb will push.</li>
 * <li><b>A real acceptance step</b> (sections 5.2, 13.B.14). {@code probe} answers
 * {@code subscriptionRequired = true}; {@code subscribe} runs
 * {@code POST /api/users/{domainUid}/subscriptions/{shareeUid}/_subscribe} <b>as the
 * grantee</b> on the container {@code mailbox:acls-<ownerUid>} (verified to be the ACL
 * container's uid, section 13.B.4; that it is also the one BlueMind's webmail
 * subscribes to is still to verify), and {@code unsubscribe} the reverse. The
 * lifecycle calls subscribe before it looks for the shared mailbox, because the share
 * may not be listable before acceptance.</li>
 * <li><b>The server e-mails the owner itself</b> (sections 5.1, 13.B.16). {@code probe}
 * answers {@code serverNotifiesOwner = true}; the lifecycle gates every owner-facing
 * notification of its own on that bit, so a BlueMind owner is told once per act.</li>
 * <li><b>Credentials and endpoint</b> (sections 3.3, 4.5, 13.B.1–2). The core API root
 * is the webmail host + {@code /api}, read from the connector's
 * {@code webMailUrl} ({@code EmailConnector.getWebMailUrl()}, a Lombok accessor) -- never
 * from {@code imapUrl}, a different host on the deployment observed. Login is
 * {@code POST /api/auth/login?login=…&origin=exo-email} with the caller's own
 * password, which is the mailbox's IMAP password; the answer's {@code authKey} rides
 * in {@code X-BM-ApiKey} for the call and is then dropped, and the same answer gives
 * {@code authUser.uid} (the mailbox uid) and {@code authUser.domainUid} (an internal id
 * such as {@code 19d43481671.internal}, not the mail domain) that every path needs.
 * The material comes from {@link org.exoplatform.emailConnector.service.acl.MailboxAclSession#httpAuthorization()},
 * the caller's own, and from nowhere else. The session class the CalDAV add-on
 * already has ({@code BlueMindRestSession}, {@code BlueMindAclClient},
 * {@code BlueMindSubscriptionClient}) is the reference shape; whether it is extracted
 * to a shared module or duplicated is the Architects Lead's call (section 3.3).</li>
 * <li><b>The grantee's uid</b> (section 3.3). The REST ACE subject is a BlueMind uid,
 * not a login; resolving it from the grantee's IMAP identifier on the owner's session
 * needs a directory call whose non-admin availability is unverified -- else the uid is
 * captured from the grantee's own login at connect time. Open; the engine must settle
 * it before its first grant.</li>
 * </ul>
 * Selection is by property ({@code email.connector.aclEngine[.<connectorId>] = bluemind}),
 * see {@link org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry}. The
 * engine is a plain {@code @Service} bean implementing the interface; nothing else is
 * needed for the registry to find it.
 */
package org.exoplatform.emailConnector.service.acl;
