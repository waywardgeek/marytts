/**
 * Copyright 2011 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 *
 * This file is part of MARY TTS.
 *
 * MARY TTS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package marytts.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;

import marytts.config.MaryConfig;
import marytts.datatypes.MaryXML;
import marytts.exceptions.MaryConfigurationException;
import marytts.modules.phonemiser.AllophoneSet;
import marytts.modules.synthesis.Voice;
import marytts.server.Mary;
import marytts.server.MaryProperties;
import marytts.util.data.audio.AudioDestination;
import marytts.util.data.audio.MaryAudioUtils;
import marytts.util.dom.MaryDomUtils;

import org.w3c.dom.Element;

/**
 * @author marc
 *
 */
public class MaryRuntimeUtils {

	public static void ensureMaryStarted() throws Exception {
		synchronized (MaryConfig.getMainConfig()) {
			if (Mary.currentState() == Mary.STATE_OFF) {
				Mary.startup();
			}
		}
	}
	
    /**
     * Instantiate an object by calling one of its constructors.
     * @param objectInitInfo a string description of the object to instantiate.
     * The objectInitInfo is expected to have one of the following forms:
     * <ol>
     *   <li> my.cool.Stuff</li>
     *   <li> my.cool.Stuff(any,string,args,without,spaces)</li>
     *   <li>my.cool.Stuff(arguments,$my.special.property,other,args)</li>
     * </ol>
     * where 'my.special.property' is a property in one of the MARY config files.
     * @return the newly instantiated object.
     * @throws ClassNotFoundException
     * @throws IllegalArgumentException
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws SecurityException
     * @throws NoSuchMethodException
     */
    public static Object instantiateObject(String objectInitInfo)
    throws MaryConfigurationException
    {
        Object obj = null;
        String[] args = null;
        String className = null;
        try {
	        if (objectInitInfo.contains("(")) { // arguments
	            int firstOpenBracket = objectInitInfo.indexOf('(');
	            className = objectInitInfo.substring(0, firstOpenBracket);
	            int lastCloseBracket = objectInitInfo.lastIndexOf(')');
	            args = objectInitInfo.substring(firstOpenBracket+1, lastCloseBracket).split(",");
	            for (int i=0; i<args.length; i++) {
	                if (args[i].startsWith("$")) {
	                    // replace value with content of property named after the $
	                    args[i] = MaryProperties.getProperty(args[i].substring(1));
	                }
	                args[i] = args[i].trim();
	            }
	        } else { // no arguments
	            className = objectInitInfo;
	        }
	        Class<? extends Object> theClass = Class.forName(className).asSubclass(Object.class);
	        // Now invoke Constructor with args.length String arguments
	        if (args != null) {
	            Class<String>[] constructorArgTypes = new Class[args.length];
	            Object[] constructorArgs = new Object[args.length];
	            for (int i=0; i<args.length; i++) {
	                constructorArgTypes[i] = String.class;
	                constructorArgs[i] = args[i];
	            }
	            Constructor<? extends Object> constructor = (Constructor<? extends Object>) theClass.getConstructor(constructorArgTypes);
	            	obj = constructor.newInstance(constructorArgs);
	        } else {
	            obj = theClass.newInstance();
	        }
	    } catch (Exception e) {
	    	// try to make e's message more informative if possible
	    	throw new MaryConfigurationException("Cannot instantiate object from '"+objectInitInfo+"': "+MaryUtils.getFirstMeaningfulMessage(e), e);
	    }
        return obj;
    }

    /**
     * Verify if the java virtual machine is in a low memory condition.
     * The memory is considered low if less than a specified value is
     * still available for processing. "Available" memory is calculated using
     * <code>availableMemory()</code>.The threshold value can be specified
     * as the Mary property mary.lowmemory (in bytes). It defaults to 20000000 bytes.
     * @return a boolean indicating whether or not the system is in low memory condition.
     */
    public static boolean lowMemoryCondition()
    {
        return MaryUtils.availableMemory() < lowMemoryThreshold();
    }

    /**
     * Verify if the java virtual machine is in a very low memory condition.
     * The memory is considered very low if less than half a specified value is
     * still available for processing. "Available" memory is calculated using
     * <code>availableMemory()</code>.The threshold value can be specified
     * as the Mary property mary.lowmemory (in bytes). It defaults to 20000000 bytes.
     * @return a boolean indicating whether or not the system is in very low memory condition.
     */
    public static boolean veryLowMemoryCondition()
    {
        return MaryUtils.availableMemory() < lowMemoryThreshold()/2;
    }

    private static long lowMemoryThreshold()
    {
        if (lowMemoryThreshold < 0) // not yet initialised
            lowMemoryThreshold = (long) MaryProperties.getInteger("mary.lowmemory", 10000000);
        return lowMemoryThreshold;
    }

    private static long lowMemoryThreshold = -1;

    /**
     * List the available audio file format types, as a multi-line string.
     * Each line consists of the name of an Audio file format type, followed by a suffix
     * "_FILE" if the format can be produced as a file, and "_STREAM" if the format can be streamed.
     * @return a multi-line string, or an empty string if no audio file types are available.
     */
    public static String getAudioFileFormatTypes()
    {
        StringBuilder output = new StringBuilder();
        AudioFileFormat.Type[] audioTypes = AudioSystem.getAudioFileTypes();
        for (int t=0; t<audioTypes.length; t++) {
            AudioFileFormat.Type audioType = audioTypes[t];
            String typeName = audioType.toString();
            boolean isSupported = true;
            if (typeName.equals("MP3")) isSupported = canCreateMP3();
            else if (typeName.equals("Vorbis")) isSupported = canCreateOgg();
            audioType = MaryAudioUtils.getAudioFileFormatType(typeName);
            if (audioType == null) {
                isSupported = false;
            }
    
            if (isSupported && AudioSystem.isFileTypeSupported(audioType))
            {
                output.append(typeName).append("_FILE\n");
                
                if (typeName.equals("MP3") || typeName.equals("Vorbis"))
                    output.append(typeName).append("_STREAM\n");
            }
        }
        return output.toString();
    }

    /**
     * Determine whether conversion to mp3 is possible.
     *
     */
    public static boolean canCreateMP3()
    {
        return AudioSystem.isConversionSupported(getMP3AudioFormat(), Voice.AF22050);
    }

    public static AudioFormat getMP3AudioFormat()
    {
        return new AudioFormat(
            new AudioFormat.Encoding("MPEG1L3"),
            AudioSystem.NOT_SPECIFIED,
            AudioSystem.NOT_SPECIFIED,
            1,
            AudioSystem.NOT_SPECIFIED,
            AudioSystem.NOT_SPECIFIED,
            false);
            //endianness doesn't matter
    }

    /**
     * Determine whether conversion to ogg vorbis format is possible.
     */
    public static boolean canCreateOgg()
    {
        return AudioSystem.isConversionSupported(getOggAudioFormat(), Voice.AF22050);
    }

    public static AudioFormat getOggAudioFormat()
    {
        return new AudioFormat(
                new AudioFormat.Encoding("VORBIS"),
                AudioSystem.NOT_SPECIFIED,
                AudioSystem.NOT_SPECIFIED,
                1,
                AudioSystem.NOT_SPECIFIED,
                AudioSystem.NOT_SPECIFIED,
                false);
    }

    
    /**
     * For an element in a MaryXML document, do what you can to determine
     * the appropriate AllophoneSet. First search for the suitable voice,
     * then if that fails, go by locale.
     * @param e
     * @return an allophone set if there is any way of determining it, or null.
     * @throws MaryConfigurationException if a suitable allophone set exists in principle, but there were problems loading it.
     */
    public static AllophoneSet determineAllophoneSet(Element e) 
    throws MaryConfigurationException {
        AllophoneSet allophoneSet = null;
        Element voice = (Element) MaryDomUtils.getAncestor(e, MaryXML.VOICE);
        Voice maryVoice = Voice.getVoice(voice);
        if (maryVoice == null) {
            // Determine Locale in order to use default voice
            Locale locale = MaryUtils.string2locale(e.getOwnerDocument().getDocumentElement().getAttribute("xml:lang"));
            maryVoice = Voice.getDefaultVoice(locale);
        }
        if (maryVoice != null) {
            allophoneSet = maryVoice.getAllophoneSet();
        } else {
            Locale locale = MaryUtils.string2locale(e.getOwnerDocument().getDocumentElement().getAttribute("xml:lang"));
            allophoneSet = determineAllophoneSet(locale);
        }
        return allophoneSet;
    }

    /**
     * Try to determine the Allophone set to use for the given locale.
     * @param allophoneSet
     * @param locale
     * @return the allophone set defined for the given locale, or null if no such allophone set can be determined.
     * @throws MaryConfigurationException if an allophone set exists for the given locale in principle, but there were problems loading it.
     */
    public static AllophoneSet determineAllophoneSet(Locale locale)
    throws MaryConfigurationException {
        AllophoneSet allophoneSet = null;
        String propertyPrefix = MaryProperties.localePrefix(locale);
        if (propertyPrefix != null) {
            String propertyName = propertyPrefix + ".allophoneset";
            allophoneSet = needAllophoneSet(propertyName);
        }
        return allophoneSet;
    }

    /**
     * The mary property setting determining where to save audio data.
     */
    private static final String audiostoreProperty = MaryProperties.getProperty("synthesis.audiostore", "ram");

    /**
     * Create an AudioDestination to which the audio data can be written.
     * Depending on the mary property "synthesis.audiostore",
     * this will use either a ByteArrayOutputStream or a FileOutputStream.
     * The calling code is responsible for administering this AudioDestination. 
     * @throws IOException if the underlying OutputStream could not be created. 
     */
    public static AudioDestination createAudioDestination() throws IOException {
    	boolean ram = false;
        if (audiostoreProperty.equals("ram")) ram = true;
        else if (audiostoreProperty.equals("file")) ram = false;
        else // auto
            if (lowMemoryCondition()) ram = false;
            else ram = true;
        return new AudioDestination(ram);
    }

    /**
     * Convenience method to access the allophone set referenced in the MARY property with the given name.
     * @param propertyName name of the property referring to the allophone set
     * @throws MaryConfigurationException if the allophone set cannot be obtained
     * @return the requested allophone set. This method will never return null; if it cannot
     * get the allophone set, it throws an exception.
     */
    public static AllophoneSet needAllophoneSet(String propertyName)
    throws MaryConfigurationException {
    	String propertyValue = MaryProperties.getProperty(propertyName);
    	if (propertyValue == null) {
    		throw new MaryConfigurationException("No such property: "+propertyName);
    	}
    	if (AllophoneSet.hasAllophoneSet(propertyValue)) {
    		return AllophoneSet.getAllophoneSetById(propertyValue);
    	}
    	InputStream alloStream;
    	try {
    		alloStream = MaryProperties.needStream(propertyName);
    	} catch (FileNotFoundException e) {
    		throw new MaryConfigurationException("Cannot open allophone stream for property "+propertyName, e);
    	}
    	assert alloStream != null;
    	return AllophoneSet.getAllophoneSet(alloStream, propertyValue);
    }
}
