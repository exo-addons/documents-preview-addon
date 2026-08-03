# documents-preview-addon

eXo Documents preview add-on. It lets users preview a document (PDF or common
office formats) directly in the browser — from the Documents app, the
Activity Stream, or any other place that plugs into the shared attachments
preview extension point — instead of downloading it first. Office documents
(Word, Excel, PowerPoint, OpenDocument, RTF, CSV...) are converted to PDF on
the server via JODConverter, then rendered client-side with PDF.js.

## Prerequisites

- An eXo Platform / Meeds distribution with the **`ecms`** and **`documents`**
  add-ons installed — this add-on relies on `DocumentFileService` (from
  `documents`) to fetch the file content and on `DMSMimeTypeResolver`
  (from `ecms`) to resolve file extensions.
- A **LibreOffice or OpenOffice** installation reachable on the server that
  hosts this add-on. It is required by JODConverter to convert office
  documents to PDF; without it, office files simply cannot be previewed
  (the REST endpoint returns `503` — see below). See the administrator's
  guide for how to install and run it in headless mode.

## Installation

Install like any other eXo add-on, e.g. via the add-ons manager:

```shell
./addon install documents-preview
```

## Configuration

All settings below are plain JVM system properties: set them in
`exo.properties` (or pass them as `-D` JVM options) and restart the server.

### JODConverter (office-to-PDF conversion)

```properties
###########################
#
# JOD Converter (Documents preview)
# Requires to have openoffice/libreoffice server installed. See administrators guide.
#

# Jod Converter activation
# Sample: exo.jodconverter.enable=false
exo.jodconverter.enable=true
# Comma separated list of ports numbers to use for open office servers used to convert documents.
# One office server instance will be created for each port.
# Sample: exo.jodconverter.portnumbers=2002,2003,2004,2005
exo.jodconverter.portnumbers=2002
# The absolute path to the office home on the server.
# Default value: NONE (Path automatically discovered based on the OS default locations)
# Sample: exo.jodconverter.officehome=/usr/lib/libreoffice
exo.jodconverter.officehome=
# The maximum living time in milliseconds of a task in the conversion queue.
exo.jodconverter.taskqueuetimeout=30000
# The maximum time in milliseconds to process a task.
exo.jodconverter.taskexecutiontimeout=120000
# The maximum number of tasks to process by an office server.
exo.jodconverter.maxtasksperprocess=200
# The interval time in milliseconds to try to restart an office server in case it unexpectedly stops.
exo.jodconverter.retrytimeout=120000
```

`exo.jodconverter.enable` defaults to `true` when unset. When disabled (or
when the office server isn't reachable), the preview REST endpoint responds
with `503 Service Unavailable` for office documents instead of converting
them; PDF files are unaffected since they don't need conversion.

### PDF preview limits

To keep conversion/rendering cheap, previews of office documents are capped
by size and page count. Both are optional; if unset, the defaults below apply.

| Property | Default | Description |
|---|---|---|
| `exo.documents.preview.max-file-size` | `10` (MB) | Above this size, the source file is not converted and the endpoint returns `413 Payload Too Large`. |
| `exo.documents.preview.max-pages` | `100` | Above this page count (checked after conversion), the endpoint returns `413 Payload Too Large`. |

```properties
exo.documents.preview.max-file-size=10
exo.documents.preview.max-pages=100
```

## Usage

Once installed and configured, no further action is needed: the add-on
registers a preview handler for PDF and office MIME types on the shared
`Preview`/`previewExtensions` extension point, so any attachment of a
supported type is automatically previewable wherever that extension point is
used (Documents app, Activity Stream attachments, etc.). Converted PDFs are
cached server-side (invalidated when the source document changes) so repeat
previews don't re-trigger a conversion.
