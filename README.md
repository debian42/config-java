# config-java
Dynamic configuration manager in java

## Build and Test
execute `test.sh` to run some basic tests, uses `build.sh` to build the stuff.
`TESTDRIVER_MEASURE=true ./test.sh`

## Explanation
I wanted to have a pure Java solution (no dependency) for "typed configuration" backed by files, which can be reloaded when the configuration on disk change. Also needs to work with Java 8. It should be like a C/C++ header only file. You drop it in your project, modify the package and it's ready to use. No jar-file, no dependency. The code could be simpler and smarter, but when I look back in 6 months I find that all my code looks terrible no matter how much SOLID/Clean-Code I use. The interface must only be used for configuration. All methods must have no parameters and only String, double, long, int, boolean are allowed as return types.

The idea is as follows:   
Two annotations are used.   
@Configurable with parameter “filePath”(relative or absolute) pointing to the configuration file in Java-Property-Format.

The annotation @ConfigurationValue must be used on the interface methods. The “key” parameter must be unique inside the file and is the key. “defaultValue” is the initial value for that key which can be overridden in the file. Different “Config-Interfaces” can refer to the same file.
```java
package de.codecoverage.grpc.impl;
public class Worker {
    @Configurable(filePath = masterconfiguration.properties)
    public interface Config {
         @ConfigurationValue(key = "de.codecoverage.grpc.impl.Worker.dumpClientResponse", defaultValue = "false")
         boolean logClientResponse ();

         @ConfigurationValue(key = "de.codecoverage.grpc.impl.Worker.logBufferSize", defaultValue = "1024")
         int getLogBufferSize();
    }
```

```java
// If the configuration changes, Config is newly generated
Config cnf = ConfigManager.get(Config.class);
…
if (cnf.logClientResponse()) {
         log……
}
```
 
For native image generation (Quarkus or similar) you must learn the ropes and find a solution.
If you use java modules, don’t forget to export the package.
I've used it in a Wildfly-Application-Server and as a standalone microservice. Each POD has its own configuration and a specific feature can be enabled or disabled for testing (as some kind of canary deployment) purposes.

[Blog](https://www.codecoverage.de/posts/java/dynconfig/)