package de.metas.adempiere.report.jasper;

/*
 * #%L
 * de.metas.report.jasper.server.base
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.Check;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.VFS;
import org.compiere.model.Query;
import org.compiere.util.CLogMgt;
import org.compiere.util.CLogger;
import org.compiere.util.Env;

import de.metas.adempiere.report.jasper.model.I_AD_OrgInfo;

/**
 * Jasper class loader: basically it will resolve {@link #PLACEHOLDER} from resource names and will fetch the resources from remote HTTP servers.
 */
final class JasperClassLoader extends ClassLoader
{
	// services
	private static final transient CLogger logger = CLogger.getCLogger(JasperClassLoader.class);

	public static final String PLACEHOLDER = "@PREFIX@";

	private String prefix;
	private boolean alwaysPrependPrefix = false;

	// Hooks
	private final OrgLogoClassLoaderHook logoHook;

	public JasperClassLoader(final int adOrgId, final ClassLoader parent)
	{
		super(parent);
		this.prefix = retrieveReportPrefix(adOrgId);
		this.logoHook = OrgLogoClassLoaderHook.forAD_Org_ID(adOrgId);
	}

	private static final String retrieveReportPrefix(final int adOrgId)
	{
		final String whereClause = I_AD_OrgInfo.COLUMNNAME_AD_Org_ID + "=?";
		final I_AD_OrgInfo orgInfo = new Query(Env.getCtx(), I_AD_OrgInfo.Table_Name, whereClause, ITrx.TRXNAME_None)
				.setParameters(adOrgId)
				.firstOnly(I_AD_OrgInfo.class);
		final String reportPrefix = orgInfo.getReportPrefix();

		logger.config("ReportPrefix: " + reportPrefix + " (AD_Org_ID=" + adOrgId + ")");

		return reportPrefix;
	}

	@Override
	protected URL findResource(final String name)
	{
		// guard against null
		if (Check.isEmpty(name, true))
		{
			return null;
		}

		final String urlStr = convertResourceNameToURLString(name);
		try
		{
			final URL url = new URL(urlStr);
			if (CLogMgt.isLevel(Level.FINE))
				logger.fine("URL: " + url + " for " + name);
			return url;
		}
		catch (MalformedURLException e)
		{
			logger.log(Level.WARNING, "Got invalid URL '" + urlStr + "' for '" + name + "'. Returning null.", e);
		}
		return null;
	}

	@Override
	public URL getResource(String name)
	{
		final URL url = logoHook.getResourceURLOrNull(name);
		if (url != null)
		{
			return url;
		}

		return super.getResource(name);
	}

	@Override
	public InputStream getResourceAsStream(final String name)
	{
		//
		// Get resource's URL
		final URL url = getResource(name);
		if (CLogMgt.isLevel(Level.FINE))
			logger.fine("URL: " + url + " for " + name);
		if (url == null)
		{
			return null; // no resource URL found
		}

		try
		{
			final FileSystemManager fsManager = VFS.getManager();

			// TODO add provider for ADempiere attachments

			final FileObject jasperFile = fsManager.resolveFile(url.toString());

			final FileContent jasperData = jasperFile.getContent();

			final InputStream is = jasperData.getInputStream();

			// copy the stream data to a local stream
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			JasperUtil.copy(is, out);
			is.close();
			jasperFile.close();

			final InputStream result = new ByteArrayInputStream(out.toByteArray());

			return result;
		}
		catch (org.apache.commons.vfs2.FileNotFoundException e)
		{
			logger.log(Level.INFO, "Resource not found. Skipping.", e);
			return null;
		}
		catch (FileSystemException e)
		{
			logger.log(Level.WARNING, "Error while retrieving bytes for resource " + url + ". Skipping.", e);
			return null;
		}
		catch (IOException e)
		{
			throw new AdempiereException("IO error while retrieving bytes for resource " + url, e);
		}
	}

	/**
	 * Converts given resource name to URL string. Mainly it will parse {@link #PLACEHOLDER}.
	 * 
	 * @param resourceName
	 * @return resource's URL string
	 */
	private final String convertResourceNameToURLString(final String resourceName)
	{
		if (Check.isEmpty(prefix))
		{
			return resourceName;
		}

		final StringBuilder urlStr = new StringBuilder();

		if (resourceName.startsWith(PLACEHOLDER))
		{

			if (resourceName.startsWith(PLACEHOLDER + "/"))
			{

				urlStr.append(resourceName.replace(PLACEHOLDER, prefix));

			}
			else
			{

				if (prefix.endsWith("/"))
				{
					urlStr.append(resourceName.replace(PLACEHOLDER, prefix));
				}
				else
				{
					urlStr.append(resourceName.replace(PLACEHOLDER, prefix + "/"));
				}
			}
			alwaysPrependPrefix = true;
			return urlStr.toString();

		}
		else
		{
			final Pattern pattern = Pattern.compile("@([\\S]+)@([\\S]+)");
			final Matcher matcher = pattern.matcher(resourceName);

			if (matcher.find())
			{

				prefix = matcher.group(1);
				urlStr.append(prefix);

				final String report = matcher.group(2);

				if (!prefix.endsWith("/") && !report.startsWith("/"))
				{
					urlStr.append("/");
				}
				urlStr.append(report);
				alwaysPrependPrefix = true;

				return urlStr.toString();
			}
		}

		if (alwaysPrependPrefix)
		{
			urlStr.append(prefix);

			if (!prefix.endsWith("/") && !resourceName.startsWith("/"))
			{
				urlStr.append("/");
			}
		}
		urlStr.append(resourceName);
		return urlStr.toString();
	}
}
