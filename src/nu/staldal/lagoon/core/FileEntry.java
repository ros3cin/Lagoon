/*
 * Copyright (c) 2001, Mikael St�ldal
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the author nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE REGENTS OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *
 * Note: This is known as "the modified BSD license". It's an approved
 * Open Source and Free Software license, see
 * http://www.opensource.org/licenses/
 * and
 * http://www.gnu.org/philosophy/license-list.html
 */

package nu.staldal.lagoon.core;

import java.io.*;
import java.util.*;
import java.net.URL;
import java.net.URLConnection;

import javax.xml.parsers.*;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import org.xml.sax.*;

import nu.staldal.lagoon.util.*;


/**
 * A file in the sitemap.
 *
 * Contains information on how to (re)build a single file in a website.
 *
 * @see nu.staldal.lagoon.core.Sitemap
 */
class FileEntry extends EntryWithSource implements SitemapEntry, FileTarget
{
    private static final boolean DEBUG = true;

    private ByteStreamProducer myProducer;

    private final FileStorage targetStorage;
	private final File tempDir;
	private final String targetURL;

    private String currentSourceURL;
    private String currentTargetURL;
    private String currentTargetDir;
    private String currentTargetName;
    private long targetLastMod;
    private String newTarget;
	private Vector tempFiles;

	
    /**
     * Constructor.
     *
     * @param processor the LagoonProcessor.
     * @param sitemap  the Sitemap.
     * @param targetURL  the file to create, may contain wildcard anywhere,
     *                   must be pseudo-absolute.
     * @param sourceURL  the file to use, may contain wildcard in filename,
	 *                  must absolute or pseudo-absolute, may be <code>null</code>.
     * @param sourceRootDir  absolute path to the source directory
     * @param targetStorage  where to store generated files
	 * @param tempDir	where to store temporary files
     */
    public FileEntry(LagoonProcessor processor, Sitemap sitemap, 
					 String targetURL, String sourceURL,
                     File sourceRootDir, FileStorage targetStorage, File tempDir)
        throws LagoonException
    {
		super(processor, sitemap, sourceURL, sourceRootDir);	
		
        this.targetStorage = targetStorage;
		this.tempDir = tempDir;
		this.targetURL = targetURL;
		
        this.myProducer = null;

        this.currentSourceURL = null;
        this.currentTargetURL = null;
        this.targetLastMod = -1;
        this.newTarget = null;
    }


    /**
     * Set the ByteStreamProducer that produces the final output for this
     * FileEntry.
     * Used during initialization.
     */
    void setMyProducer(ByteStreamProducer prod)
    {
        myProducer = prod;
    }


    /**
     * Builds this particular file.
     *
     * @param always  always build the file, overriding dependency checking
     */
    public void build(boolean always)
        throws IOException
    {
        // System.out.println("Building " + target + " from " + source +
        //                   (always ? " always" : ""));

		if (sourceURL == null)
        {   // no main source
	        currentSourceURL = null;
            currentTargetURL = targetURL;
            buildFile(always);
        }
        if (LagoonUtil.absoluteURL(sourceURL))
        {   // absolute URL
	        currentSourceURL = sourceURL;
            currentTargetURL = targetURL;
            buildFile(always);
        }
        else if (Wildcard.isWildcard(sourceURL))
        {   // main source is a wildcard pattern
			int slash = sourceURL.lastIndexOf('/');
			String sourceDirURL = sourceURL.substring(0, slash+1);
			String sourceMask = sourceURL.substring(slash+1);
			File sourceDir = new File(sourceRootDir, sourceDirURL); 
            
			String[] files = sourceDir.list();
			if (files == null)
			{
				throw new FileNotFoundException(
					sourceDir.getAbsolutePath() + " (directory not found)");	
			}
            for (int i = 0; i < files.length; i++)
            {				
                File currentSourceFile = new File(sourceDir, files[i]);
                if (!currentSourceFile.isFile()) continue;

                String part = Wildcard.matchWildcard(sourceMask, files[i]);
                if (part == null) continue;

				currentSourceURL = sourceDirURL + files[i];

                currentTargetURL =
                    Wildcard.instantiateWildcard(targetURL, part);
                buildFile(always);
            }
        }
        else
        {   // main source is a regular file
	        currentSourceURL = sourceURL;
            currentTargetURL = targetURL;
            buildFile(always);
        }
    }
	

    private void buildFile(boolean always)
        throws IOException
    {
        // System.out.println("buildFile: " + currentTargetURL);

        targetLastMod = targetStorage.fileLastModified(currentTargetURL);

        if (always || (targetLastMod <= 0))
        {
            buildAlways();
            return;
        }

        boolean updated = false;
        try {
            updated = myProducer.hasBeenUpdated(targetLastMod);
        }
        catch (LagoonException e)
        {
            reportException(e);
        }
        catch (IOException e)
        {
            reportException(e);
        }

        if (updated)
        {
            buildAlways();
        }
    }
	

    /**
     * The actual building of this file.
     * Used after any dependency checking indicates the file needs rebuilding.
     */
    private void buildAlways()
        throws IOException
    {
        if (DEBUG) System.out.println("Building: " + currentTargetURL);

		int slash = currentTargetURL.lastIndexOf('/');
		currentTargetDir = currentTargetURL.substring(0, slash+1);
		currentTargetName = currentTargetURL.substring(slash+1);

        String thisTargetURL;
        OutputHandler out = null;
        String exceptionType = null;
        boolean bailOut = false;

		tempFiles = new Vector();
		
		newTarget = currentTargetName;

        do {
			thisTargetURL = currentTargetDir + newTarget;
            newTarget = null;
	        out = targetStorage.createFile(thisTargetURL);
			
			try {
                myProducer.start(out.getOutputStream(), this);
                exceptionType = null; // no exception thrown
            }
            catch (Exception e)
            {
				// if (DEBUG) e.printStackTrace();	
				
				String thisExceptionType = e.getClass().getName();

				// the same type of exception thrown twice in a row
				if (thisExceptionType.equals(exceptionType))
				{
					bailOut = true;
				}
				exceptionType = thisExceptionType;

                reportException(e);

				if (out != null) out.discard();				

				if (e instanceof RuntimeException)
				{
					throw (RuntimeException)e;
				}

                if (bailOut)
                {
					System.out.println("Error building " + currentTargetURL
						+ ": Too many exceptions, bailing out");
					break;
				}
				else
				{
					continue;
				}
            }

            out.commit();
        } while (newTarget != null);

        byte[] buf = new byte[8192];
		
		for (int i = 0; i<tempFiles.size(); i++)
		{
			String path = (String)tempFiles.elementAt(i);
			File tempFile = new File(tempDir, "temp" + i);
			FileInputStream fis = new FileInputStream(tempFile);
			
			OutputHandler oh = targetStorage.createFile(path);
	
			try {
    	    	while (true)
        		{
            		int bytesRead = fis.read(buf);
            		if (bytesRead < 1) break;
            		oh.getOutputStream().write(buf, 0, bytesRead);
        		}
	        	fis.close();
				tempFile.delete();
			}
			catch (IOException e)
			{
				reportException(e);
				oh.discard();
				break;
			}
			oh.commit();			
		}
    }


	private void reportException(Exception e)
	{
		if (e instanceof SAXParseException)
		{
			SAXParseException spe = (SAXParseException)e;
			String sysId = (spe.getSystemId() == null)
				? ("(" + currentTargetURL + ")"): spe.getSystemId();
			System.out.println(sysId + ":" + spe.getLineNumber()
				+ ":" + spe.getColumnNumber() + ": " + spe.getMessage());
		}
		else if (e instanceof SAXException)
		{
			SAXException se = (SAXException)e;
			Exception ee = se.getException();
			if (ee != null)
			{
				System.out.println("Error building " + currentTargetURL
					+ ": " + ee.toString());
			    if (DEBUG) ee.printStackTrace(System.out);
			}
			else
			{
				System.out.println("Error building " + currentTargetURL
					+ ": " + se.getMessage());
    			if (DEBUG) se.printStackTrace(System.out);
			}
		}
		else if (e instanceof IOException)
		{
			System.out.println("Error building " + currentTargetURL
					+ ": " + e.toString());
			if (DEBUG) e.printStackTrace(System.out);
		}
		else
		{
			System.out.println("Error building " + currentTargetURL + ":");
			e.printStackTrace(System.out);
		}
	}


	// Partial SourceManager implemenation
   
    public String getSourceURL()
        throws FileNotFoundException
	{
        if (currentSourceURL == null)
            throw new FileNotFoundException("no source file specified");

		return currentSourceURL;		
	}
	
	
	// FileTarget implemenation

    public String getCurrentTargetURL()
    {
        return currentTargetURL;
    }

    public void newTarget(String filename, boolean prependFilename)
    {
		if (prependFilename)
		{
			this.newTarget = currentTargetName + '_' + filename;	
		}
		else
		{
			this.newTarget = filename;
		}
    }

    public OutputHandler newAsyncTarget(String filename, boolean prependFilename)
		throws IOException
	{
		if (filename.charAt(0) != '/' && !prependFilename)
		{
			filename = currentTargetDir + filename;
		}
		else if (filename.charAt(0) != '/' && prependFilename)
		{			
			filename = currentTargetDir + currentTargetName + '_' + filename;
		}
			
		if (DEBUG) System.out.println("New async target: " + filename);
		
		if (targetStorage.isReentrant())
		{
			return targetStorage.createFile(filename);
		}
		else
		{
			tempFiles.addElement(filename);			
			File currentFile = new File(tempDir, "temp" + (tempFiles.size()-1));	
			return new TempOutputHandler(currentFile,
				new FileOutputStream(currentFile));
		}
	}	
	
	public boolean isWildcard()
    {
        return Wildcard.isWildcard(sourceURL);
	}


	static class TempOutputHandler extends OutputHandler
	{
		private File currentFile;
		
		TempOutputHandler(File currentFile, OutputStream out)
		{
			super(out);
			this.currentFile = currentFile;
		}
		
		public void commit()
			throws java.io.IOException
		{
			out.close();
		}

		public void discard()
			throws java.io.IOException
		{
			out.close();
			if (!currentFile.exists()) return;
			if (currentFile.delete())
			{
				return;
			}
			else
			{
				throw new IOException("Unable to delete file: " + currentFile);
			}
		}
	}
	
}

