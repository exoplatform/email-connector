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

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What came of carrying a forwarded message's files onto the draft that forwards it:
 * the draft as it now stands, and the files that were left behind.
 * <p>
 * The second half is the whole reason this is not simply an {@link Email}. A forward
 * of a message bigger than one mail may carry cannot take everything, and the two
 * alternatives to saying so are both worse than a second field: attaching them all
 * produces a draft that can never be sent, and dropping them silently produces a
 * forward whose sender believes the files went with it — which is the very defect
 * this feature exists to fix, reintroduced one layer up.
 * <p>
 * The names are answered rather than counted because the sender has to be able to act
 * on them: knowing that "two files were left behind" tells them nothing about whether
 * the important one is among them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForwardedAttachments {

  /**
   * The draft, attachments included, exactly as every other draft call answers with
   * it — so the composer rebuilds its chips from one shape and not from two.
   */
  private Email        draft;

  /**
   * The names of the forwarded message's files that were NOT attached — refused by the
   * size a message may carry, or unreadable. Empty when everything came across, which
   * is the ordinary case.
   * <p>
   * One list rather than one per cause, deliberately. The sender's question is "what is
   * not on this forward", and the answer is the same list whichever way a file was
   * lost; WHY it was lost is diagnostics and belongs in the log, where the reason is
   * recorded against each of them.
   */
  private List<String> notAttached;
}
