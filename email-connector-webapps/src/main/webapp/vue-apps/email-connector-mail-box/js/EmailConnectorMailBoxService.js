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

const presentation = {
  class: 'fas fa-file-powerpoint',
  color: '#CB4B32',
};
const sheet = {
  class: 'fas fa-file-excel',
  color: '#217345',
};
const word = {
  class: 'fas fa-file-word',
  color: '#2A5699',
};
const image = {
  class: 'fas fa-file-image',
  color: '#999999',
};
const video = {
  class: 'fas fa-file-video',
  color: '#79577A',
};
const audio = {
  class: 'fas fa-file-audio',
  color: '#79577A',
};
const archive = {
  class: 'fas fa-file-archive',
  color: '#717272',
};
const code = {
  class: 'fas fa-file-code',
  color: '#6cf500',
};
const pdf = {
  class: 'fas fa-file-pdf',
  color: '#FF0000',
};
const text = {
  class: 'fas fa-file-alt',
  color: '#385989',
};
const illustration = {
  class: 'fas fa-file-contract',
  color: '#E79E24',
};
const file = {
  class: 'fas fa-file',
  color: '#476A9C',
};
const folder = {
  class: 'fas fa-folder',
  color: '#476A9C',
};
const attachmentMapIconsExtensions = new Map([
  ['application/pdf', pdf],
  ['application/vnd.ms-powerpoint', presentation],
  ['application/vnd.openxmlformats-officedocument.presentationml.presentation', presentation],
  ['application/vnd.oasis.opendocument.presentation', presentation],
  ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', sheet],
  ['application/vnd.oasis.opendocument.spreadsheet', sheet],
  ['officedocument.spreadsheetml.sheet', sheet],
  ['application/vnd.ms-excel', sheet],
  ['text/csv', sheet],
  ['application/vnd.openxmlformats-officedocument.wordprocessingml.document', word],
  ['application/msword', word],
  ['application/rtf', word],
  ['application/vnd.oasis.opendocument.text', word],
  ['text/plain', text],
  ['image/webp', image],
  ['image/avif', image],
  ['image/bmp', image],
  ['image/gif', image],
  ['image/jpeg', image],
  ['image/png', image],
  ['image/tiff', image],
  ['image/svg+xml', image],
  ['video/x-msvideo', video],
  ['video/mp4', video],
  ['video/mpeg', video],
  ['video/ogg', video],
  ['video/webm', video],
  ['video/3gpp', video],
  ['video/quicktime', video],
  ['audio/mpeg', audio],
  ['audio/ogg', audio],
  ['audio/wav', audio],
  ['application/zip', archive],
  ['application/vnd.rar', archive],
  ['application/rar', archive],
  ['application/x-zip', archive],
  ['application/java-archive', archive],
  ['application/postscript', illustration],
  ['text/html', code],
  ['text/xml', code],
  ['application/xml', code],
  ['text/css', code],
  ['file', file],
  ['folder', folder],
]);
 
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

export function deleteEmail(mailRemoteId) {
  return fetch(`/email-connector/rest/email-box/${mailRemoteId}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'DELETE'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when deleting email');
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

export function updateEmailReadStatus(mailRemoteId, readStatus) {
  return fetch(`/email-connector/rest/email-box/${mailRemoteId}?readStatus=${readStatus}`, {
    headers: {
      'Content-Type': 'application/json'
    },
    credentials: 'include',
    method: 'PATCH'
  }).then((resp) => {
    if (!resp?.ok) {
      throw new Error('Error when updating email read status');
    }
  });
}

export function getAttachmentIcon(mimeType) {
  return attachmentMapIconsExtensions.get(mimeType.toLowerCase()) || file;
}

export async function downloadAttachment(attachment, signal) {
  const mailId = attachment.mailRemoteId;
  const attachId = attachment.attachmentRemoteId;
  const url = `/email-connector/rest/email-box/attachments/${mailId}/${attachId}`;
  try {
    const response = await fetch(url, { signal });
    if (!response.ok) {
      throw new Error('Error when downloading attachment');
    }
    const contentDisp = response.headers.get('Content-Disposition');
    let filename = attachment.name;
    if (contentDisp) {
      const match = contentDisp.match(/filename\*?=UTF-8''([^;]+)/);
      if (match) {
        filename = decodeURIComponent(match[1]);
      }
    }
    const blob = await response.blob();
    const blobUrl = URL.createObjectURL(blob);
    triggerDownload(blobUrl, filename);
    setTimeout(() => {
      URL.revokeObjectURL(blobUrl);
    }, 60000);
  } catch (e) {
    if (e.name !== 'AbortError') {
      console.error('Error when downloading attachment:', e);
    }
  }
}

export function triggerDownload(fileUrl, filename) {
  const link = document.createElement('a');
  link.href = fileUrl;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
}