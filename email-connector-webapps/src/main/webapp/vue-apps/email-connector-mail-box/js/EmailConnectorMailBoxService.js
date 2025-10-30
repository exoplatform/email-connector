/*
 * Copyright (C) 2025 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
 
export function getEmailBox() {
  return fetch('/email-connector/rest/email-box', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting email box');
    }
  });
}

export function getEmailByRemoteId(mailRemoteId) {
  return fetch(`/email-connector/rest/email-box/${mailRemoteId}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'GET'
  }).then((resp) => {
    if (resp?.ok) {
      return resp.json();
    } else {
      throw new Error('Error when getting email detail');
    }
  });
}

export function synchronize() {
  return fetch('/email-connector/rest/email-box/synchronization', {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'POST'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when synchronizing email box');
    }
  });
}

export function formatDateString(dateToFormat, yesterdayLabel) {
  const today = new Date();
  today.setHours(0,0,0,0);
  const resetDateToFormat = new Date(dateToFormat);
  resetDateToFormat.setHours(0,0,0,0);
  let options = {};
  const localeOfUser = eXo.env.portal.language.replace('_', '-');
  const differenceInDays = Math.abs(today.getTime() - resetDateToFormat.getTime()) / (24*60*60*1000);
  if (differenceInDays === 0) { // In today
    options = {
      hour: '2-digit', 
      minute: '2-digit'
    };
    return new Date(dateToFormat).toLocaleTimeString(localeOfUser, options);
  }
  else if (differenceInDays === 1) { // In yesterday
    return yesterdayLabel;
  }
  else if (differenceInDays < 7) { // In the same week
    options = {
      weekday: 'long'
    };
    return new Date(resetDateToFormat).toLocaleDateString(localeOfUser, options).replace(/^\p{L}/u, c => c.toUpperCase());
  } else if (differenceInDays < 31) {// In the last 31 days
    options = {
      weekday: 'short',
      style: 'short',
      month: 'short',
      day: 'numeric',
    };
    return new Date(resetDateToFormat.getTime()).toLocaleDateString(localeOfUser, options);
  } else {
    options = {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
    };
    return new Date(resetDateToFormat.getTime()).toLocaleDateString(localeOfUser, options);
  }
}