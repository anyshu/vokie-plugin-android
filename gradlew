#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
