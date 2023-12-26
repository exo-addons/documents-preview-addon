# documents-preview-addon
eXo Documents preview addon

Those are the properties to be added to the file exo.properties to configure JodConverter
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
# The maximum living time in milliseconds of a task in the conversation queue.
exo.jodconverter.taskqueuetimeout=30000
# The maximum time in milliseconds to process a task.
exo.jodconverter.taskexecutiontimeout=120000
# The maximum number of tasks to process by an office server.
exo.jodconverter.maxtasksperprocess=200
# The interval time in milliseconds to try to restart an office server in case it unexpectedly stops.
exo.jodconverter.retrytimeout=120000
```
