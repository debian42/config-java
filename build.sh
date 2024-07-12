#!/bin/bash

if [ ! -d "bin" ]; then
  mkdir -p "bin"
fi

javac -cp src/de/codecoverage/config/ -d bin src/de/codecoverage/config/ConfigManager.java src/de/codecoverage/config/TestDriver.java
