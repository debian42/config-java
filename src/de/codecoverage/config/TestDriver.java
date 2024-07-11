package de.codecoverage.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Properties;

import de.codecoverage.config.ConfigManager.Configurable;
import de.codecoverage.config.ConfigManager.ConfigurationValue;

//
// Test driver
//  
public class TestDriver {
    private static final String PATH_CONFIG1= "duckhawk.properties";
    private static final String PATH_CONFIG2="test/duckhawk.properties";
   
    @Configurable(filePath = PATH_CONFIG1)
    public interface TESTConfig1 {
         @ConfigurationValue(key = "de.codecoverage.base.impl.LoggingRestServiceFilter.clientResponse", defaultValue = "false")
         boolean logClientRestCallResponse();
 
         @ConfigurationValue(key = "de.codecoverage.base.impl.LoggingRestServiceFilter.clientRequest", defaultValue = "false")
         boolean logClientRestCallRequest();
         
         @ConfigurationValue(key = "de.codecoverage.base.impl.LoggingRestServiceFilter.clientRequest", defaultValue = "12345678")
         boolean getIntValue();
    }
    
    @Configurable(filePath = PATH_CONFIG1)
    public interface TESTConfigFailParameter {
         @ConfigurationValue(key = "de.codecoverage.base.Test.clientRequest", defaultValue = "false")
         boolean failBecauseOfParameters(String temp);
    }
    
    @Configurable(filePath = PATH_CONFIG1)
    public interface TESTConfigFailBoolean {
         @ConfigurationValue(key = "de.codecoverage.base.Test.clientResponse", defaultValue = "false")
         Boolean failBecauseOfBoolean();
    }
   
    @Configurable(filePath = PATH_CONFIG2)
    public interface TESTConfig2 {
         @ConfigurationValue(key = "TEST1_b", defaultValue = "false")
         boolean getBoolean();
 
         @ConfigurationValue(key = "TEST2_s", defaultValue = "String")
         String getString();
    }
    
    @Configurable(filePath = "@SYSTEM_PROPERTY_CONFIG@")
    public interface TESTConfigProperty {
         @ConfigurationValue(key = "codecoverage.de.config.test_string", defaultValue = "a simple string, must be the same as default")
         String getString();
    }
 
	public static void changeOrAddContent(String path, String key, String value) {
		Properties prop = new Properties();
		Path p = Paths.get(path);

		try (BufferedReader br = Files.newBufferedReader(p)) {
			prop.load(br);
			prop.setProperty(key, value);
			try (BufferedWriter bw = Files.newBufferedWriter(p)) {
				for (String pkey : prop.stringPropertyNames()) {
					bw.write(pkey + "=" + prop.getProperty(pkey));
					bw.newLine();
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static void comment4Ever(String path, String key) {
		Path p = Paths.get(path);
		ArrayList<String> stringArray = new ArrayList<>();
		try (BufferedReader br = Files.newBufferedReader(p)) {
			String data = br.readLine();
			while (data != null) {
				stringArray.add(data);
				data = br.readLine();
			}

			for (int i = 0; i < stringArray.size(); i++) {
				String line = stringArray.get(i);
				if (line.matches("^#*" + key +".*$")) {
					stringArray.remove(i);
					stringArray.add(i, "#" + line);
				}
			}
			try (BufferedWriter bw = Files.newBufferedWriter(p)) {
				for (String pkey : stringArray) {
					bw.write(pkey);
					bw.newLine();
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void main(String... args) throws Exception {
		int count = args.length == 1 ? Integer.parseInt(args[0]) : 1;
		for (int n = 0 ; n < count ; n++) {
			System.out.println(n);
			
			//fail
			try {
				TESTConfigFailBoolean fail = ConfigManager.get(TESTConfigFailBoolean.class);
				fail.failBecauseOfBoolean();
			} catch(Exception e) {
				if (e.getMessage().contains("wrong return type"))
				{ /* ignore */ } else {
					throw new RuntimeException("failed");
				}
			}
			try {
				TESTConfigFailParameter fail = ConfigManager.get(TESTConfigFailParameter.class);
				fail.failBecauseOfParameters("fail");
			} catch(Exception e) {
				if (e.getMessage().contains("has parameters") )
				{ /* ignore */ } else {
					throw new RuntimeException("failed");
				}
			}
			
			System.setProperty("SYSTEM_PROPERTY_CONFIG", PATH_CONFIG2);
			TESTConfig2 test2 = ConfigManager.get(TESTConfig2.class);
			TESTConfigProperty systemProperty = ConfigManager.get(TESTConfigProperty.class);
			TESTConfig1 caller1;
			TESTConfig1 caller2;
			TESTConfig1 caller3;
			
			// OSR, get a feeling for performance 
			boolean measure = Boolean.parseBoolean(System.getenv("TESTDRIVER_MEASURE"));
			if (measure) {
				for (int i = 0; i < 200000; i++) {
					long t1 = System.nanoTime();
					caller2 = ConfigManager.get(TESTConfig1.class);
					String str = caller2.logClientRestCallRequest() ? "true" : "false";
					long t2 = System.nanoTime();				
					if (t2-t1 <= 80) {
						System.out.println(i + " :  " + str + "   took " + (t2-t1) + " ns");
						break;
					}
				}
			}
			
			caller1 = ConfigManager.get(TESTConfig1.class);
			caller2 = ConfigManager.get(TESTConfig1.class);

			if (caller1 != caller2) {
				throw new IllegalArgumentException("caller1 != caller2. CACHE NOT WORKING");
			}

			// Change the file content and wait 2s
			String old = test2.getString();
			changeOrAddContent(PATH_CONFIG1, "de.codecoverage.base.impl.LoggingRestServiceFilter.clientRequest", Boolean.toString(!caller2.logClientRestCallRequest()));
			changeOrAddContent(PATH_CONFIG2, "TEST2_s", String.valueOf(System.currentTimeMillis()));
			Thread.sleep(250); // propagate change (give WINDOWS a little bit more time...)
			TESTConfig2 test2New = ConfigManager.get(TESTConfig2.class);
			if (old.equals(test2New.getString()))
				throw new IllegalArgumentException("test2.getString() not changed old: " + old + "  test2New: " + test2New.getString());
			
			if (test2 == test2New) {
				throw new IllegalArgumentException("test2 == test2New. CACHE NOT WORKING!");
			}
			caller3 = ConfigManager.get(TESTConfig1.class);

			if (caller2 == caller3) {
				throw new IllegalArgumentException("caller2 == caller3. CACHE NOT WORKING!");
			}
			changeOrAddContent(PATH_CONFIG2, "#IGNORE", "COMMENTS");
			Thread.sleep(150); // propagate change
			TESTConfig2 ignore1 = ConfigManager.get(TESTConfig2.class);
			comment4Ever(PATH_CONFIG2, "IGNORE"); // change triggered and reload, but no instantiation of new class
			Thread.sleep(150); // propagate change
			TESTConfig2 ignore2 = ConfigManager.get(TESTConfig2.class);		
			if (ignore1 != ignore2) {
				throw new IllegalArgumentException("ignore1 != ignore2. CACHE NOT WORKING! Comment triggered re-creation");
			}

			TESTConfigProperty systemPropertyAgain = ConfigManager.get(TESTConfigProperty.class);
			//System.out.println(systemPropertyAgain.getString());
			
			if (systemPropertyAgain != systemProperty) {
				throw new IllegalArgumentException("CACHE NOT WORKING! TESTConfigProperty must no be re-created");
			}
		}
		ConfigManager.shutdown();
	}
}
