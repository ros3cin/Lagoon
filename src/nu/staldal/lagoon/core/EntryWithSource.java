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
 *
 */
abstract class EntryWithSource implements SourceManager
{
    private static final boolean DEBUG = true;

	protected final Sitemap sitemap;
    protected final File sourceRootDir;

    protected final String sourceURL;

	protected final SAXParserFactory spf;	
	

    /**
     * Constructor.
     *
     * @param sitemap  the Sitemap.
     * @param sourceURL  the file to use, may contain wildcard in filename,
	 *                   must absolute or pseudo-absolute, may be <code>null</code>.
     * @param sourceRootDir  absolute path to the source directory
     */
    public EntryWithSource(Sitemap sitemap, String sourceURL, File sourceRootDir)
        throws LagoonException
    {
		this.sitemap = sitemap;

        String absPath = sourceRootDir.getAbsolutePath();
        this.sourceRootDir = new File(absPath);
        if (!this.sourceRootDir.isDirectory())
            throw new LagoonException(
                "sourceRootDir must be an existing directory: " + sourceRootDir);

		if (!LagoonUtil.absoluteURL(sourceURL) 
				&& !LagoonUtil.pseudoAbsoluteURL(sourceURL))
		{
        	throw new LagoonException(
				"source must be absolute or pseudo-absolute");
		}

		this.sourceURL = sourceURL;

		spf = SAXParserFactory.newInstance();
		spf.setNamespaceAware(true);
		spf.setValidating(false);
    }


	// SourceManager implemenation

    public File getRootDir()
    {
        return sourceRootDir;
    }

    
    public String getSourceURL()
        throws FileNotFoundException
	{
        if (sourceURL == null)
            throw new FileNotFoundException("no source file specified");

		return sourceURL;		
	}
	
	
    public InputStream openFile(String url)
        throws FileNotFoundException, IOException
    {
		File file = getFile(url);
		
		if (file == null)
		{
			URL theUrl = new URL(url);
			URLConnection uc = theUrl.openConnection();
			return uc.getInputStream();
		}
		else
		{
			return new FileInputStream(file);
		}
    }


    public File getFile(String url)
        throws FileNotFoundException
    {
		if (LagoonUtil.absoluteURL(url))
		{
			if (url.startsWith("file:"))
			{
				return new File(url.substring(5));	
			}
			else if (url.startsWith("res:"))
			{
				String resDir = System.getProperty("resourceDir");
				if (resDir == null)
					throw new FileNotFoundException(
						"Resource Dir is not specified");
				return new File(new File(resDir), url.substring(5));
			}
			else
			{
				return null;
			}
		}
		else
		{
			return new File(sourceRootDir,
        	    getFileURL(url).substring(1).replace('/', File.separatorChar));
		}
    }

	
    public Source getFileAsJAXPSource(String url)
        throws FileNotFoundException
	{
		File file = getFile(url);	
		
		if (file == null)
			return new StreamSource(getFileURL(url));
		else
			return new StreamSource(file);
	}
	
	
	public void getFileAsSAX(String url, ContentHandler ch, Target target)
		throws IOException, SAXException
	{
		if (LagoonUtil.absoluteURL(url) && url.startsWith("part:"))
		{
			PartEntry pe = sitemap.lookupPart(url.substring(5));
			if (pe == null)
				throw new FileNotFoundException("Part " + url + " not found");

			pe.getXMLProducer().start(ch, target);
			return;				
		}
			
		InputSource is = new InputSource(getFileURL(url));
		InputStream istream = null;
		
		File file = getFile(url);	
		
		if (file != null)
		{
			istream = new FileInputStream(file);
			is.setByteStream(istream);	
		}

		try {
			XMLReader parser = spf.newSAXParser().getXMLReader(); 

			parser.setContentHandler(ch);

			parser.parse(is);
		}
		catch (ParserConfigurationException e)
		{
			throw new SAXException(e);
		}
		finally
		{
			if (istream != null) istream.close();
		}		
	}
	
	
    public String getFileURL(String url)
        throws FileNotFoundException
    {
		return getFileURLRelativeTo(url, getSourceURL());
	}


    public boolean fileHasBeenUpdated(String url, long when)
        throws FileNotFoundException, IOException, LagoonException
    {
        File file = getFile(url);
		if (file == null)
		{
			if (LagoonUtil.absoluteURL(url) && url.startsWith("part:"))
			{
				PartEntry pe = sitemap.lookupPart(url.substring(5));
				if (pe == null)
					throw new FileNotFoundException("Part " + url + " not found");

				return pe.getXMLProducer().hasBeenUpdated(when);
			}
			else
				return true;  // cannot check
		}
        long sourceDate = file.lastModified();

        return ((sourceDate > 0) // source exsist
                &&
                // will also build if (when == -1) (i.e. unknown)
                (sourceDate > when));
    }

	
    public boolean canCheckFileHasBeenUpdated(String url)
	{
		return !LagoonUtil.absoluteURL(url) 
			|| url.startsWith("part:")
			|| url.startsWith("file:")
			|| url.startsWith("res:");
	}


    public String getFileURLRelativeTo(String url, String base)
    {
        if (LagoonUtil.absoluteURL(url) || LagoonUtil.pseudoAbsoluteURL(url))
		{
            return url;
		}
        else
        {
            if (!LagoonUtil.pseudoAbsoluteURL(base))
                throw new IllegalArgumentException(
					"base must be a pseudo-absolute URL");

            int slash = base.lastIndexOf('/');
            String baseDir = base.substring(0, slash+1);

            return baseDir + url;
        }
    }
	
}
