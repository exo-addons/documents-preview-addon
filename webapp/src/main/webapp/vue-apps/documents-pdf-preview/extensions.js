/*
 * This file is part of the Meeds project (https://meeds.io/).
 * 
 * Copyright (C) 2020 - 2025 Meeds Association contact@meeds.io
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

const supportedFormats =   [
  'application/pdf',
  'application/msword',
  'application/ppt',
  'application/vnd.ms-powerpoint',
  'application/rtf',
  'application/vnd.oasis.opendocument.graphics',
  'application/vnd.oasis.opendocument.presentation',
  'application/vnd.oasis.opendocument.spreadsheet',
  'application/vnd.oasis.opendocument.spreadsheet-template',
  'application/vnd.oasis.opendocument.text',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.openxmlformats-officedocument.presentationml.template',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document.form',
  'application/vnd.sun.xml.impress',
  'application/vnd.sun.xml.writer',
  'application/wordperfect',
  'application/xls',
  'application/vnd.ms-excel',
  'application/xlt',
  'text/csv',
  'application/vnd.oasis.opendocument.formula'];

for (let i = 0; i < supportedFormats.length; i++) {
  extensionRegistry.registerExtension('Preview', 'previewExtensions', {
    id: `${supportedFormats[i]}-preview`,
    fileType: supportedFormats[i],
    rank: 15,
    componentOptions: {
      vueComponent: Vue.options.components['attachments-pdf-preview'],
    },
  });
}