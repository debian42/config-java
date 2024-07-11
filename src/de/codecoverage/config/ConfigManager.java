// Adapt the package to your structure:
package de.codecoverage.config;
 
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
 
/**
 * The Main-Class with its static get() Method
 * Config conf = ConfigManager.get(Config.class);
 * conf.getBoolean();
 */
public final class ConfigManager {
 
    @Documented
    @Target(TYPE)
    @Retention(RUNTIME)
    public @interface Configurable {
         // If encapsulated with "@" then treat it as a SystemProperty. E.g. ="@cwc.dev.csa.config.ccconfig.path@"
         // and the value of cwc.dev.csa.config.ccconfig.path will be used as the path to the configuration file.
         String filePath() default "";
    }
   
    @Documented
    @Target(METHOD)
    @Retention(RUNTIME)
    public @interface ConfigurationValue {
         String key();
         String defaultValue();
    }
 
    private static final class Pair<T1, T2>
    {
    	public static <T1,T2> Pair<T1, T2> create(T1 p1, T2 p2) {
    		return new Pair<>(p1,p2);
    	}
    	
    	public final T1 p1;
    	public final T2 p2;
    	
    	private Pair(T1 p1, T2 p2) {
    		this.p1 = p1;
    		this.p2 = p2;
    	}

    	@Override
    	public String toString() { return p1 + " -> " + p2; }
    	
    	@Override
    	public int hashCode() {
    		final int prime = 31;
    		int result = 1;
    		result = prime * result + ((p1 == null) ? 0 : p1.hashCode());
    		result = prime * result + ((p2 == null) ? 0 : p2.hashCode());
    		return result;
    	}

    	@Override
    	public boolean equals(Object obj) {
    		if (this == obj)
    			return true;
    		if (obj == null)
    			return false;
    		if (getClass() != obj.getClass()) // LSP okay, because of final class
    			return false;
    		Pair<?, ?> other = (Pair<?, ?>) obj;
    		if (p1 == null) {
    			if (other.p1 != null)
    				return false;
    		} else if (!p1.equals(other.p1))
    			return false;
    		if (p2 == null) {
    			if (other.p2 != null)
    				return false;
    		} else if (!p2.equals(other.p2))
    			return false;
    		return true;
    	}
    }
    private static final Thread WATCHER_THREAD = createWatchThread();
    private static final ConcurrentHashMap<Class<?>, Pair<Object, Pair<String, Boolean>>> CACHE = new ConcurrentHashMap<>(16);
    private static final ConcurrentHashMap<Path, ConcurrentHashMap<Path, Boolean>> DIRS2WATCH = new ConcurrentHashMap<>(8);
    private static final Logger LOG = Logger.getLogger(ConfigManager.class.getName());
    private static final Map<Class<?>, Function<String, ?>> CONVERTERS = new HashMap<>();
    static {
         CONVERTERS.put(int.class, Integer::valueOf);
         CONVERTERS.put(Integer.class, Integer::valueOf);
         CONVERTERS.put(String.class, Function.identity());
         CONVERTERS.put(boolean.class, Boolean::parseBoolean);
         CONVERTERS.put(Boolean.class, Boolean::parseBoolean);
         CONVERTERS.put(long.class, Long::valueOf);
         CONVERTERS.put(Long.class, Long::valueOf);
         CONVERTERS.put(double.class, Double::valueOf);
         CONVERTERS.put(Double.class, Double::valueOf);
         CONVERTERS.put(float.class, Float::valueOf);
         CONVERTERS.put(Float.class, Float::valueOf);
         CONVERTERS.put(short.class, Short::valueOf);
         CONVERTERS.put(Short.class, Short::valueOf);
         CONVERTERS.put(char.class, value -> value.isEmpty() ? null : value.charAt(0));
         CONVERTERS.put(Character.class, value -> value.isEmpty() ? null : value.charAt(0));
         CONVERTERS.put(byte.class, Byte::valueOf);
         CONVERTERS.put(Byte.class, Byte::valueOf);
    }
    private static final WatchService watchService = getWatchService();
 
    private static volatile boolean closeFileSystemThreadLoop = false;
 
    private ConfigManager() {
    }
 
    private static WatchService getWatchService() {
         try {
             assert WATCHER_THREAD != null;
             return FileSystems.getDefault().newWatchService();
         } catch (IOException e) {
             LOG.log(Level.SEVERE, e.getMessage(), e); // Game over
         }
         return null;
    }
 
    private static Thread createWatchThread() {
         Thread thr = new Thread(() -> {
             Thread currentThread = Thread.currentThread();
             String thrName = currentThread.getName();
             while (!closeFileSystemThreadLoop) {
                 try {
                     if (!DIRS2WATCH.isEmpty()) {
                    	  List<Path> folders = DIRS2WATCH.keySet().stream().collect(Collectors.toList());
                          currentThread.setName("ConfigManager waiting for file changes in " + folders);
                          WatchKey wk = watchService.take();
                          Path directory = Path.class.cast(wk.watchable());
                          List<WatchEvent<?>> events = wk.pollEvents();
                          for (WatchEvent<?> event : events) {
                              Path fileName = (Path) event.context(); // file name
                              for (Entry<Path, ConcurrentHashMap<Path, Boolean>> dir : DIRS2WATCH.entrySet()) {
                                  if (directory.equals(dir.getKey()) && dir.getValue().containsKey(fileName)) {
                                      LOG.info(() -> "File: '" + fileName + "' has changed in directory '" + dir.getKey() + "' Reloading all configurations.");
                                      currentThread.setName("reloadAll");
                                      // When a file changes, we trigger a reload of all configuration files. 
                                      // This is not optimal, but internally we check whether the properties have really changed
                                      try {
                                    	  reloadAll();
                                      } catch (Exception e) {
                                    	  // Don't just let it die just because someone made a typo on a number
                                    	  LOG.log(Level.SEVERE, e.getMessage(), e);
                                      }
                                  }
                              }
                          }
                          wk.reset();
                     } else {
                          Thread.sleep(100); // Don't hog the CPU if we have no files to watch
                     }
                 } catch (InterruptedException | ClosedWatchServiceException e) {
                     // ignore !  LOG.log(Level.SEVERE, e.getMessage(), e);
                 } finally {
                     currentThread.setName(thrName);
                 }
             }
         });
         thr.setDaemon(true);
         thr.start();
         return thr;
    }
 
    private static void reloadAll() {
         CACHE.entrySet().stream()
             .filter(e -> e.getValue().p2.p2)
             .forEach(e -> getIntern(e.getKey(), true));
    }
    
	private static String getReturnSignature(Class<?> type) {
		if (type == String.class)       return "()Ljava/lang/String;";
		else if (type == boolean.class) return "()Z";
		else if (type == int.class) return "()I";
		else if (type == long.class)    return "()J";
		else if (type == double.class)  return "()D";
		throw new IllegalArgumentException("wrong return type: " + type);
	}
 
    /**
    * Get an object which has the concrete configuration
    *
    * @param interfaceClass the "config" interface to create
    * @return an Object implementing this interface
    */
    public static <T> T get(Class<T> interfaceClass) {
         return getIntern(interfaceClass, false);
    }
 
    @SuppressWarnings("unchecked")
    private static <T> T getIntern(Class<T> interfaceClass, boolean forceRemove) {
         Pair<Object, Pair<String, Boolean>> p = CACHE.get(interfaceClass);
         if (p == null || forceRemove) {
             Pair<Object, Pair<String, Boolean>> value = createConcreteObject(interfaceClass, p, forceRemove);
             p = CACHE.putIfAbsent(interfaceClass, value);
             if (p == null) {
                 p = value;
             }
         }
         return (T) p.p1;
    }
 
    private static <T> Pair<Object, Pair<String, Boolean>> createConcreteObject(Class<T> interfaceClass, Pair<Object, Pair<String, Boolean>> in, boolean forceRemove)
    {
         if (!interfaceClass.isInterface()) {
             throw new IllegalArgumentException(interfaceClass + " not an interface");
         }
 
         if (!interfaceClass.isAnnotationPresent(Configurable.class)) {
             throw new IllegalArgumentException("Interface:" + interfaceClass + " not annotated with Configurable");
         }
 
         boolean isFineLogging = LOG.isLoggable(Level.FINE);
         String providerString = "";
 
         Annotation annotation = interfaceClass.getAnnotation(Configurable.class);
         Configurable cc = (Configurable) annotation;
         providerString = cc.filePath();
         HashMap<Method, Object> map = new HashMap<>();
         Properties properties = loadPropertyFile(providerString, interfaceClass);
         StringBuilder sb = new StringBuilder();
        
         for (Method method : interfaceClass.getDeclaredMethods()) {
             if (method.isAnnotationPresent(ConfigurationValue.class)) {
                 Annotation annotationPath = method.getAnnotation(ConfigurationValue.class);
                 ConfigurationValue ccPath = (ConfigurationValue) annotationPath;
                 String path = ccPath.key();
                 String value = ccPath.defaultValue();
                 Object overrideDefault = properties.get(path);
 
                 if (overrideDefault != null)
                     value = overrideDefault.toString();
 
                 sb.append(path).append('=').append(value);
                 Object v = null;
				 try {
					 v = convert(value, method.getReturnType());
				 } catch (Exception e) {
						throw new IllegalArgumentException("convert() failed: " + path + "=" + value + " returnType:" + method.getReturnType(), e);
				 }
                 if (method.getParameterCount() != 0)
                	 throw new IllegalArgumentException("Method: " + method + " has parameters");
                 
                 map.put(method, v);
             } else {
            	 String msg = "Method: " + method + " has no annotation 'ConfigurationPath'";
                 LOG.severe(msg);
                 throw new IllegalArgumentException(msg);
             }
         }
 
         // We donate cpu time and memory!
         // If the parameters didn't change after reload, we don't want to create a new
         // class. We also don't want to create a new class/instance and destroy all the work yet done
         // by the jit
         String storeString = sb.toString();
         if (in != null && storeString.equals(in.p2.p1)) {
             if (isFineLogging)
                 LOG.fine("No configuration changes detected: " + providerString);
             return in;
         } else {
             LOG.warning("!Configuration changes detected! : " + providerString);
         }
 
         // getIntern gets the key, or if removing was faster, we repeat this 
         // and save the new value only if it hasn't already been inserted by
         // the other... Should be safe regarding null pointer
         if (forceRemove) {
             CACHE.remove(interfaceClass);
             if (isFineLogging)
                 LOG.fine(() -> "removed interface '" + interfaceClass + "' from cache");
         }
 
         Object obj = null;
         try {
        	Map<String, String> methods = new HashMap<>();
			for (Entry<Method, Object> entry : map.entrySet()) {
				Method m = entry.getKey();
				Object value = entry.getValue();
				String methodName = m.getName();
				String methodSignature = getReturnSignature(m.getReturnType());
				methods.put(methodName, methodSignature + "-" + value);
			}
			String iName = interfaceClass.getName().replace('.','/');
			obj = ConfigManager.getInstance(iName, methods);
         } catch (Exception t) {
             LOG.log(Level.SEVERE, "Handcrafted class generation failed!");
             throw new IllegalArgumentException(t);
         }
 
         return Pair.create(obj, Pair.create(storeString, Boolean.TRUE));
    }   

    private static Properties loadPropertyFile(String providerString, Class<?> interfaceClass) {
         assert providerString != null;
         assert interfaceClass != null;
         if (providerString.startsWith("@") && providerString.endsWith("@")) {
             providerString = providerString.substring(1, providerString.length() - 1);
             providerString = System.getProperty(providerString);
             if (providerString == null) {
                 LOG.log(Level.SEVERE, "System property not found for the given key.");
                 return new Properties();
             }
         }
 
         Path path = Paths.get(providerString);
         Properties prop = new Properties();
 
         if (!Files.exists(path)) {
             LOG.log(Level.SEVERE, () -> "File not found: " + path.toAbsolutePath());
             return new Properties();
         }
 
         try (BufferedReader br = Files.newBufferedReader(path.toAbsolutePath())) {
             prop.load(br);
             Path directory = path.toAbsolutePath().getParent();
 
             synchronized (DIRS2WATCH) {
                 ConcurrentHashMap<Path, Boolean> files = DIRS2WATCH.get(directory);
                 if (files == null) {
                     files = new ConcurrentHashMap<>(8);
                     DIRS2WATCH.put(directory, files);
                     directory.register(watchService, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
                 }
                 files.put(path.getFileName(), Boolean.FALSE);
             }
         } catch (IOException e) {
             LOG.log(Level.SEVERE, "loadPropertyFile: for config interface " + interfaceClass.getName() + " failed! Path=" + path.toAbsolutePath(), e);
         }
 
         return prop;
    }
 
 
	private static Object convert(String value, Class<?> returnType) {
		Function<String, ?> converter = CONVERTERS.get(returnType);
		if (converter != null) {
			return converter.apply(value);
		} else {
			throw new IllegalArgumentException("No converter for: " + returnType + " defined!");
		}
	}
 
    public static void shutdown() {
         CACHE.clear();
         DIRS2WATCH.clear();
         try {
             closeFileSystemThreadLoop = true;
             watchService.close();
         } catch (IOException e) {
             e.printStackTrace();
         }
    }
    
    //// CCLLAASSGGEENN
    private static class ClassGenerator {
        private static final int MAGIC = 0xCAFEBABE;
        private static final short MINOR_VERSION = 0;
        private static final short MAJOR_VERSION = 52; // Java 8
        private static final short ACC_PUBLIC = 0x0001;
        private static final short ACC_FINAL = 0x0010;
        private static final byte CONSTANT_UTF8 = 1;
        private static final byte CONSTANT_INTEGER = 3;
        private static final byte CONSTANT_LONG =5;
        private static final byte CONSTANT_DOUBLE =	6;
        private static final byte CONSTANT_CLASS = 7;
        private static final byte CONSTANT_STRING_REF = 8;
        private static final byte CONSTANT_NAME_AND_TYPE = 12;
        private static final byte CONSTANT_METHOD_REF = 10;
        private static final String DELIMITER = "-";
        private static final boolean DUMP_CLASS_FILE = false;

        private static class Clazz {
            private static class MethodInfo {
                short accessFlags = ACC_PUBLIC;
                short attrCount = 1;
                short maxStack = 2;
                short maxLocals = 1;
                Function<Clazz, byte[]> code;
                short exceptionTableLength = 0;
                short attributesCount = 0;
            }

            private Clazz() {
                addUtf8Constant("Code");
            }

            private Map<String, Short> strings      = new LinkedHashMap<>();
            private Map<String, Short> stringRefs   = new LinkedHashMap<>();
            private List<Integer> intRefs           = new ArrayList<>();
            private List<Double> doubleRefs         = new ArrayList<>();            
            private List<Long> longRefs             = new ArrayList<>();
            private Map<String, Short> classes      = new LinkedHashMap<>();
            private Map<String, MethodInfo> methods = new LinkedHashMap<>();
            private short index           = 1;
            private String thisClass      = "";
            private String superClass     = "";
            private String interfaceClass = "";
            private static byte[] returnBoolFalse = new byte[]{(byte) 0x03, (byte) 0xAC};
            private static byte[] returnBoolTrue  = new byte[]{(byte) 0x04, (byte) 0xAC};
           
            private static byte[] encodeModifiedUTF8(String input)
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length() * 2);
                for (int i = 0; i < input.length(); i++) {
                    int c = input.charAt(i);
                    if (c >= 0x0001 && c <= 0x007F) {
                        // 1-byte sequence (ASCII range)
                        baos.write(c);
                    } else if (c == 0x0000) {
                        // Special handling for null character
                        baos.write(0xC0);
                        baos.write(0x80);
                    } else if (c <= 0x07FF) {
                        // 2-byte sequence
                        baos.write(0xC0 | (c >> 6));
                        baos.write(0x80 | (c & 0x3F));
                    } else {
                        // 3-byte sequence
                        baos.write(0xE0 | (c >> 12));
                        baos.write(0x80 | ((c >> 6) & 0x3F));
                        baos.write(0x80 | (c & 0x3F));
                    }
                }

                return baos.toByteArray();
            }

            private void thisClass(String name) {
                thisClass = name;
                addClassConstant(name);
            }

            private void superClass(String name) {
                superClass = name;
                addClassConstant(name);
            }
            
            private void interfaceClass(String name) {
                interfaceClass = name;
                addClassConstant(name);
            }

            private short getClassPos(String value) {
                short i = 1;
                for (Entry<String, Short> cc : classes.entrySet()) {
                    if (cc.getKey().equals(value))
                        return i;
                    i++;
                }
                return -1;
            }

            private short getStringPos(String value) {
                short i = 1;
                for (Entry<String, Short> cc : strings.entrySet()) {
                    if (cc.getKey().equals(value))
                        return i;
                    i++;
                }
                return -1;
            }

            private short getStringRefPos(String value) {
                short i = 1;
                for (Entry<String, Short> cc : stringRefs.entrySet()) {
                    if (cc.getKey().equals(value))
                        return i;
                    i++;
                }
                return -1;
            }
            
            private short getIntRefPos(Integer value) {
                short i = 1;
                for (Integer cc : intRefs) {
                    if (cc.equals(value))
                        return i;
                    i++;
                }
                return -1;
            }
            
            private short getDoubleRefPos(Double value) {
                short i = 1;
                for (Double cc : doubleRefs) {
                    if (cc.equals(value))
                        return i;
                    i++;i++;
                }
                return -1;
            }
            
            private short getLongRefPos(Long value) {
                short i = 1;
                for (Long cc : longRefs) {
                    if (cc.equals(value))
                        return i;
                    i++;i++;
                }
                return -1;
            }

            private byte[] getClassBytes() throws IOException {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
                DataOutputStream data = new DataOutputStream(baos);
                data.writeInt(MAGIC);
                data.writeShort(MINOR_VERSION);
                data.writeShort(MAJOR_VERSION);
                short constPoolEnd = (short)(strings.size() + classes.size() + stringRefs.size() + intRefs.size() + (doubleRefs.size()*2) + (longRefs.size()*2)); 
                data.writeShort(constPoolEnd + 1 + 2); // pool size

                //
                // construct constant pool
                //
                // 1. strings
                for (Entry<String, Short> strs : strings.entrySet()) {
                    data.writeByte(CONSTANT_UTF8);
                    byte[] utf8Bytes = encodeModifiedUTF8(strs.getKey());
                    data.writeShort(utf8Bytes.length);
                    data.write(utf8Bytes);
                }
                // 2. classes
                for (Entry<String, Short> cls : classes.entrySet()) {
                    data.writeByte(CONSTANT_CLASS);
                    data.writeShort(cls.getValue());
                }
                // 3. stringRefs
                for (Entry<String, Short> cls : stringRefs.entrySet()) {
                    data.writeByte(CONSTANT_STRING_REF);
                    data.writeShort(cls.getValue());
                }
                // 4. intRefs
                for (Integer v : intRefs) {
                    data.writeByte(CONSTANT_INTEGER);
                    data.writeInt(v);
                }                
                // 5. doubleRefs
                for (Double cls : doubleRefs) {
                    data.writeByte(CONSTANT_DOUBLE);
                    data.writeDouble(cls);
                }
                // 6. longRefs
                for (Long cls : longRefs) {
                    data.writeByte(CONSTANT_LONG);
                    data.writeLong(cls);
                }

                // 12 = NameAndType        #10:#11        // "<init>":()V
                data.writeByte(CONSTANT_NAME_AND_TYPE);
                data.writeShort(getStringPos("<init>"));
                data.writeShort(getStringPos("()V"));
                // 10 = Methodref          #4.#12         // java/lang/Object."<init>":()V
                data.writeByte(CONSTANT_METHOD_REF);
                data.writeShort(getClassPos(superClass) + strings.size());
                data.writeShort(constPoolEnd + 1);
                short ctorMethodRef = (short) (constPoolEnd + 2);
                
                data.writeShort(ACC_PUBLIC | ACC_FINAL);                   // Write class access flags
                data.writeShort(getClassPos(thisClass) + strings.size());  // Write this class
                data.writeShort(getClassPos(superClass) + strings.size()); // Write super class
                if (!"".equals(interfaceClass)) {
                    data.writeShort(1); // Write interfaces count
                    data.writeShort(getClassPos(interfaceClass) + strings.size()); // Write interfaces
                } else {
                    data.writeShort(0); // Write interfaces count=0
                }
                data.writeShort(0);                                        // Write fields count

                // Write methods
                data.writeShort(methods.size());                           // Write methods count
                for (Entry<String, MethodInfo> m : methods.entrySet())
                {
                    MethodInfo methodInfo = m.getValue();
                    data.writeShort(methodInfo.accessFlags);
                    String[] key = m.getKey().split("@");
                    String name = key[0];
                    String desc = key[1];
                    data.writeShort(getStringPos(name));
                    data.writeShort(getStringPos(desc));
                    data.writeShort(methodInfo.attrCount);
                    // Code attribute
                    data.writeShort(getStringPos("Code"));
                    byte[] code = methodInfo.code.apply(this);
                    data.writeInt(code.length + 12);       // Attribute length
                    data.writeShort(methodInfo.maxStack);  // Max stack
                    data.writeShort(methodInfo.maxLocals); // Max locals
                    data.writeInt(code.length);

                    if("<init>@()V".equals(m.getKey())) {
                        // patch method ref
                        code[2]= (byte)(ctorMethodRef >> 8 & 0xFF);
                        code[3]= (byte)(ctorMethodRef & 0xFF);
                    }
                    data.write(code);
                    data.writeShort(methodInfo.exceptionTableLength); // Exception table length
                    data.writeShort(methodInfo.attributesCount);      // Attributes count
                }
                data.writeShort(0);                                        // Write attributes count

                return baos.toByteArray();
            }

            private short addClassConstant(String value) {
                short idx = addUtf8Constant(value);
                return classes.computeIfAbsent(value, s -> idx);
            }

            private short addStringRefConstant(String value) {
                short idx = addUtf8Constant(value);
                return stringRefs.computeIfAbsent(value, s -> idx);
            }
            
            private void addIntRefConstant(int value) {
    			for (Integer cc : intRefs) {
    				if (cc.equals(value))
    					return;
    			}
    			intRefs.add(value);
    		}
            
            private void addLongRefConstant(long value) {
    			for (Long cc : longRefs) {
    				if (cc.equals(value))
    					return;
    			}
    			longRefs.add(value);
    		}
            
            private void addDoubleRefConstant(double value) {
    			for (Double cc : doubleRefs) {
    				if (cc.equals(value))
    					return;
    			}
    			doubleRefs.add(value);
    		}

            private short addUtf8Constant(String value) {
                return strings.computeIfAbsent(value, s -> {
                    short pos = index;
                    index++;
                    return pos;
                });
            }

            private void addDoubleMethod(String name, String descriptor, short accessFlags, double retValue)
            {
            	addDoubleRefConstant(retValue);
                addMethod(name, descriptor, accessFlags, cls -> returnDoubleConstant(cls, retValue));
            }
            
            private void addIntMethod(String name, String descriptor, short accessFlags, int retValue)
            {
            	addIntRefConstant(retValue);
                addMethod(name, descriptor, accessFlags, cls -> returnIntConstant(cls, retValue));
            }
            
            private void addLongMethod(String name, String descriptor, short accessFlags, long retValue)
            {
            	addLongRefConstant(retValue);
                addMethod(name, descriptor, accessFlags, cls -> returnLongConstant(cls,retValue));
            }
            
            private void addBooleanMethod(String name, String descriptor, short accessFlags, boolean retValue)
            {
                addMethod(name, descriptor, accessFlags, retValue ? cls -> returnBoolTrue : cls -> returnBoolFalse);
            }

            private void addStringMethod(String name, String descriptor, short accessFlags, String retValue)
            {
                addStringRefConstant(retValue);
                addMethod(name, descriptor, accessFlags, clz -> returnStringConstant(clz,retValue));
            }

            private void addMethod(String name, String descriptor, short accessFlags, Function<Clazz, byte[]> codeFunc)
            {
                assert !name.isEmpty();
                assert !descriptor.isEmpty();

                addUtf8Constant(name);
                addUtf8Constant(descriptor);

                String keyIdx = name + "@" + descriptor;
                methods.computeIfAbsent(keyIdx, s -> {
                    MethodInfo mInfo = new MethodInfo();
                    mInfo.accessFlags = accessFlags;
                    mInfo.code = codeFunc;
                    return mInfo;
                });
            }
            
            private static byte[] callObjectCTor() {
            	return new byte[] {
            		(byte) 0x2A,    // aload_0
                    (byte) 0xB7,    // invokespecial
                    (byte) 0xFF,    // Methodref index for Object.<init>
                    (byte) 0xFF,	// patch in later
                    (byte) 0xB1};   // return
            	}
            private static byte[] returnStringConstant(Clazz cls, String value) {
                short idx = cls.getStringRefPos(value);
                idx = (short) (idx + cls.strings.size() + cls.classes.size());
                //  ldc_w #idx,  areturn
                byte[] buffer = {0,0,0,0};
                buffer[0] = (byte) 0x13;
                buffer[1] = (byte)(idx >> 8 & 0xFF);
                buffer[2] = (byte)(idx & 0xFF);
                buffer[3] = (byte) 0xb0;
                return buffer;
            }
            
            private static byte[] returnIntConstant(Clazz cls, Integer value) {
                short idx = cls.getIntRefPos(value);
                idx = (short) (idx + cls.stringRefs.size() + cls.strings.size() + cls.classes.size());
                //  ldc_w #idx,  ireturn
                byte[] buffer = {0,0,0,0};
                buffer[0] = (byte) 0x13;
                buffer[1] = (byte)(idx >> 8 & 0xFF);
                buffer[2] = (byte)(idx & 0xFF);
                buffer[3] = (byte) 0xac;
                return buffer;
            }
            
            private static byte[] returnLongConstant(Clazz cls, Long value) {
                short idx = cls.getLongRefPos(value);
                idx = (short) (idx + (cls.doubleRefs.size()*2) + cls.stringRefs.size() + cls.strings.size() + cls.classes.size() + cls.intRefs.size());
                //  ldc2_w #idx,  lreturn
                byte[] buffer = {0,0,0,0};
                buffer[0] = (byte) 0x14;
                buffer[1] = (byte)(idx >> 8 & 0xFF);
                buffer[2] = (byte)(idx & 0xFF);
                buffer[3] = (byte) 0xad;
                return buffer;
            }
            
            private static byte[] returnDoubleConstant(Clazz cls, Double value) {
                short idx = cls.getDoubleRefPos(value);
                idx = (short) (idx + cls.intRefs.size() + cls.stringRefs.size() + cls.strings.size() + cls.classes.size());
                //  ldc2_w #idx,  dreturn
                byte[] buffer = {0,0,0,0};
                buffer[0] = (byte) 0x14;
                buffer[1] = (byte)(idx >> 8 & 0xFF);
                buffer[2] = (byte)(idx & 0xFF);
                buffer[3] = (byte) 0xaf;
                return buffer;
            }
            }
        }
        
        private static String calcClassName(String className) {
            return className + "$CG" + System.currentTimeMillis() + "$" +System.nanoTime();
        }
        
        private static byte[] generateClass(String className, String interfaceName, Map<String, String> methods)
        {
        	ClassGenerator.Clazz clazz = new ClassGenerator.Clazz();
            clazz.thisClass(className);
            clazz.superClass("java/lang/Object");
            clazz.interfaceClass(interfaceName);
            clazz.addMethod("<init>", "()V", ClassGenerator.ACC_PUBLIC, cls -> ClassGenerator.Clazz.callObjectCTor());
            // to test with empty main method : clazz.addMethod("main", "([Ljava/lang/String;)V", (short)(ClassGenerator.ACC_PUBLIC | 0x0008 /*ACC_STATIC*/), cls -> new byte[]{(byte) 0xB1});
            for (Entry<String, String> m : methods.entrySet()) {
                int indexOf = m.getValue().indexOf(ClassGenerator.DELIMITER);
                String retType = m.getValue().substring(0, indexOf);
                String retValue = m.getValue().substring(indexOf + 1);
                switch (retType) {
                    case "()D": // double
                        clazz.addDoubleMethod(m.getKey(), retType, ClassGenerator.ACC_PUBLIC, Double.parseDouble(retValue));
                        break;
                    case "()I": // int
                        clazz.addIntMethod(m.getKey(), retType, ClassGenerator.ACC_PUBLIC, Integer.parseInt(retValue));
                        break;
                    case "()J": // long
                        clazz.addLongMethod(m.getKey(), retType, ClassGenerator.ACC_PUBLIC, Long.parseLong(retValue));
                        break;
                    case "()Z": // boolean
                        clazz.addBooleanMethod(m.getKey(), retType, ClassGenerator.ACC_PUBLIC, Boolean.parseBoolean(retValue));
                        break; // String ref
                    case "()Ljava/lang/String;":
                        clazz.addStringMethod(m.getKey(), retType, ClassGenerator.ACC_PUBLIC, retValue);
                        break;
                    default: throw new IllegalArgumentException(retType + " not defined");
                }
            }

            try {
				return clazz.getClassBytes();
			} catch (IOException e) {
				throw new IllegalArgumentException(e);
			}
        }

        private static class ByteArrayLoader extends ClassLoader {
            private byte[] classData;

            private ByteArrayLoader(ClassLoader parent, byte[] classData) {
                super(parent);
                this.classData = classData;
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                try {
                    return super.findClass(name);
                } catch (ClassNotFoundException e) {
                    // bring it to life
                    return defineClass(name, classData, 0, classData.length);
                }
            }
        }
 
    	@SuppressWarnings("unchecked")
    	private static <T> T getInstance(String interfaceName, Map<String, String> methods) {
    		try {
    			String className = calcClassName(interfaceName);
    			byte[] classData = generateClass(className, interfaceName, methods);
    			if (ClassGenerator.DUMP_CLASS_FILE) {
					try (FileOutputStream fos = new FileOutputStream(className.replace('/', '.') + ".class")) {
						fos.write(classData);
					}
    			}
    			ByteArrayLoader classLoader = new ByteArrayLoader(ConfigManager.class.getClassLoader(), classData);
    			Class<?> loadedClass = classLoader.loadClass(className.replace("/", "."));
    			return (T) loadedClass.getDeclaredConstructor().newInstance();
    		} catch (Exception e) {
    			throw new IllegalArgumentException(e);
    		}
    	}

    	//
    	// Some tests regarding class generation
    	//    	
    	public static interface TestInterface {
    		int getInteger1();
    		boolean getBoolean();
    		String getString();
    		long getLong();
    		double getDouble();
    		double getDouble1();
    		double getDouble2();
    		long getLong1();
    		boolean getBooleanFalse();
    		int getInteger();
    	}
    	
        public static void main(String... args) throws IOException {
            String className = calcClassName("MyGeneratedClass"); // package classgen
            String interfaceName = TestInterface.class.getName().replace('.','/'); 
            Map<String, String> methods = new HashMap<>();
            methods.put("getBoolean", "()Z-TruE");
            methods.put("getInteger1", "()I--1");
            methods.put("getString", "()Ljava/lang/String;-F@€...-Döich!");
            methods.put("getDouble", "()D-123456.4444444");
            methods.put("getDouble1", "()D-654321.4444444");
            methods.put("getLong", "()J-281474976710655");
            methods.put("getDouble2", "()D-123456.4444444");
            methods.put("getLong1", "()J--1");
            methods.put("getBooleanFalse", "()Z-Nöeee");
            methods.put("getInteger", "()I-12345678");
            byte[] classData = generateClass(className, interfaceName, methods);

            try (FileOutputStream fos = new FileOutputStream(className.replace('/','.') + ".class")) {
                fos.write(classData);
                ByteArrayLoader classLoader = new ByteArrayLoader(ConfigManager.class.getClassLoader(), classData);
                Class<?> loadedClass = classLoader.loadClass(className.replace("/","."));
                TestInterface instance = (TestInterface) loadedClass.getDeclaredConstructor().newInstance();
                PrintStream o = System.out;
                o.println("instance.getBoolean() : " + instance.getBoolean());
                o.println("instance.getString()  : " + instance.getString());
                o.println("instance.getDouble()  : " + instance.getDouble());
                o.println("instance.getDouble1() : " + instance.getDouble1());
                o.println("instance.getLong()    : " + instance.getLong());
                o.println("instance.getDouble2() : " + instance.getDouble2());
                o.println("instance.getLong()1   : " + instance.getLong1());
                o.println("instance.getBoolean() : " + instance.getBooleanFalse());
                o.println("instance.getInteger() : " + instance.getInteger());
                o.println("instance.getInteger1(): " + instance.getInteger1());
            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalArgumentException(e);
            }
        }
   }