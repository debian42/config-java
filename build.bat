if not exist "bin" mkdir "bin"

javac -cp src/de/codecoverage/config/ -d bin src/de/codecoverage/config/ConfigManager.java src/de/codecoverage/config/TestDriver.java
