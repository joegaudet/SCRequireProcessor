package com.joegaudet.require;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RequireParser - a simple class for parsing through a SproutCore app and
 * identifying the requires likely needed for the app to work properly
 * @author Joe Gaudet - joe@joegaudet.com
 */
public class RequireProcessor {

	/**
	 * The path to the SC app
	 */
	private final String path;

	/**
	 * The pattern used to identify a definition
	 */
	private Pattern definitiondefinition;
	
	/**
	 * The pattern used to parse a the file name that
	 * SC will use
	 */
	private Pattern filePattern;
	
	/**
	 * The Pattern used to identify usage of a class in a 
	 * SC app
	 */
	private Pattern usagePattern;

	/** 
	 * The pattern user to identify requires that should be managed mannually
	 */
	private Pattern annotationPattern;
	
	/**
	 * Map that contains which files have the definitions for which SC classes
	 */
	private final Map<String,String> fileMap;

	/**
	 * The handler used when parsing for file definitions
	 */
	private SproutCoreFileHandler definitionsHandler;

	/**
	 * The handler used when parsing for usage
	 */
	private SproutCoreFileHandler usageHandler;
	
	/**
	 * RequireParser - a simple class for parsing through a SproutCore app and
	 * identifying the requires likely needed for the app to work properly
	 * @param path
	 * @param appName
	 */
	public RequireProcessor(String path, String appName){
		this.definitiondefinition = Pattern.compile(appName + "\\.(\\w+)\\s*=\\s*[ [.*\\.extend] | [{] ]+");
		this.usagePattern = Pattern.compile(appName + "\\.([A-Za-z]+)");
		this.filePattern = Pattern.compile(path + "/*(.+).js$");
		
		this.annotationPattern = Pattern.compile("/\\*.*@ignore.*\\*/\\s*sc_require");
		
		this.path = path;
		this.fileMap = new HashMap<String,String>();
		
		initHandlers();
	}

	/**
	 * Initializes the file handlers
	 */
	private void initHandlers() {
		definitionsHandler = new SproutCoreFileHandler(){
			@Override
			public void handleFile(File file) {
				try {
					BufferedReader reader = new BufferedReader(new FileReader(file));
					String nextLine;
					while((nextLine = reader.readLine()) != null){
						Matcher matcher = definitiondefinition.matcher(nextLine);
						if(matcher.find()){
							Matcher fileMatcher = filePattern.matcher(file.getPath().toString());
							if(fileMatcher.find()){
								fileMap.put(matcher.group(1), fileMatcher.group(1));
							}
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		};
		
		this.usageHandler = new SproutCoreFileHandler(){
			@Override
			public void handleFile(File file) {
				try {
					BufferedReader reader = new BufferedReader(new FileReader(file));
					String nextLine;
					StringBuilder newFileBuilder = new StringBuilder();
					Set<String> requires = new TreeSet<String>();
					while((nextLine = reader.readLine()) != null){
						if(guardAgainstRepeats(nextLine)){
							// comments
							if(nextLine.length() > 0 && nextLine.charAt(0) != '/'){
								Matcher matcher = usagePattern.matcher(nextLine);
								while(matcher.find()){
									for(int i = 1; i < matcher.groupCount() + 1; i++){
										String requireFile = fileMap.get(matcher.group(1));

										// guard against circular definitions
										if(requireFile != null && !file.getPath().equals(path + File.separatorChar + requireFile + ".js")){
											String require = "sc_require('" + requireFile + "');\n";
											requires.add(require);
										}
									}
								}
							}
							newFileBuilder.append(nextLine);
							newFileBuilder.append("\n");
						}
					}
					
					StringBuilder finalBuilder = new StringBuilder();
					finalBuilder.append(getLicenseHeader());
					
					for(String require:requires)
						finalBuilder.append(require);
					
					finalBuilder.append(newFileBuilder.toString());
					
					FileChannel channel = new FileOutputStream(file).getChannel();
					channel.write(ByteBuffer.wrap(finalBuilder.toString().getBytes()));
					channel.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		};
	}

	/**
	 * 
	 * @throws IOException - thrown if for some reason the files doesn't really exist, or a read protected 
	 */
	public void parse() throws IOException{
		File root = new File(path);
		
		if(!root.getParentFile().getName().equals("apps")){
			System.err.println("You have not pointed SCRequireProcessor at the right directory.");
			System.err.println("The parent directory should be apps");
			return;
		}
		
		if(!root.exists() || !root.isDirectory()){
			System.err.println("Expected an actual directory at the root");
		}
		else {
			System.out.println("Scanning for definitions");
			long time = System.currentTimeMillis();
			processDirectory(root, definitionsHandler);
			System.out.println("Execution: " + (System.currentTimeMillis() - time));
			
			System.out.println("Found Definitions");
			for(String key: fileMap.keySet())
				System.out.println(key);
			
			System.out.println("Scanning for usage");
			long time3 = System.currentTimeMillis();
			processDirectory(root, usageHandler);
			System.out.println("Execution: " + (System.currentTimeMillis() - time3));
		}
	}
	
	/**
	 * Gets the license header
	 * @return The license header formated with new lines
	 */
	protected String getLicenseHeader() {
		StringBuilder builder = new StringBuilder();
		builder.append("// ==========================================================================\n");
		builder.append("// Project:   DarkHorse - The PC Browser targeted face of the Matygo Web App\n");
		builder.append("// Copyright: ©2010 Matygo Educational Incorporated\n");
		builder.append("// ==========================================================================\n");
		builder.append("/*globals DarkHorse SCUI */\n");
		return builder.toString();
	}

	/**
	 * Guards against putting the requires and the license header stuff back in twice.
	 * Could be a lto more sophisticated, but it works in a pinch
	 * @param nextLine
	 * @return
	 */
	protected boolean guardAgainstRepeats(String nextLine) {
		boolean retval = !nextLine.contains("sc_require");
		retval &= !nextLine.contains("// ==========================================================================");
		retval &= !nextLine.contains("// Project:");
		retval &= !nextLine.contains("// Copyright:");
		retval &= !nextLine.contains("/*globals");
		
		// let these ones through
		retval |= nextLine.contains("sc_require('core')");
		retval |=  annotationPattern.matcher(nextLine).find();
		return retval;

	}

	/**
	 * Processes a directory
	 * @param file
	 * @throws IOException 
	 */
	private void processDirectory(File directory, SproutCoreFileHandler handler) {
		for(File file:directory.listFiles()){
			if(file.isDirectory()){
				processDirectory(file, handler);
			}
			else {
				processFile(file, handler);
			}
		}
	}

	/**
	 * 
	 * @param file
	 * @throws IOException 
	 */
	private void processFile(File file, SproutCoreFileHandler handler) {
		// ignore core.js
		if(!file.getName().equals("core.js") && file.getName().endsWith(".js")){
			handler.handleFile(file);
		}
	}

	/**
	 * Generic interface to allow the file tree to be walked 
	 * with a different strategy each time.
	 * @author Joe Gaudet - joe@joegaudet.com
	 *
	 */
	private interface SproutCoreFileHandler {
		public void handleFile(File file);
	}
	
	/**
	 * 
	 * @param args pathToApp and AppName - eg: /Users/Joe/sc/apps/dark_horse DarkHorse
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		if(args.length != 2){
			System.err.println("USAGE: java -jar SCRequireProcessor <appDirectory> <appName>");
		}
		else {
			new RequireProcessor(args[0], args[1]).parse();
		}
		
//		Matcher matcher = Pattern.compile("DarkHorse\\.([A-Za-z]+)").matcher("DarkHorse.CommentsView = SC.View.extend(DarkHorse.MatygoView, DarkHorse.NavigationBuilder");
//		while(matcher.find()){
//			System.out.println(matcher.group(1));
//		}
	}
	
}
