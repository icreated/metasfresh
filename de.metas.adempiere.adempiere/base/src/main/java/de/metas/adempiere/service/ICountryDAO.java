/**
 *
 */
package de.metas.adempiere.service;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import java.util.List;
import java.util.Properties;

import org.adempiere.location.CountryId;
import org.adempiere.util.ISingletonService;
import org.compiere.model.I_C_Country;
import org.compiere.model.I_C_Country_Sequence;
import org.compiere.model.I_C_Region;
import org.compiere.util.Env;

import de.metas.i18n.ITranslatableString;

/**
 * @author cg
 *
 */
public interface ICountryDAO extends ISingletonService
{
	I_C_Country getById(CountryId id);

	/**
	 * retrieve custom user info
	 *
	 * @param ctx
	 * @param trxName
	 * @return
	 */
	ICountryCustomInfo retriveCountryCustomInfo(Properties ctx, String trxName);

	/**
	 * Get Default Country
	 *
	 * @param ctx
	 *            context
	 * @return Country
	 */
	I_C_Country getDefault(Properties ctx);

	default CountryId getDefaultCountryId()
	{
		return CountryId.ofRepoId(getDefault(Env.getCtx()).getC_Country_ID());
	}

	@Deprecated
	public I_C_Country get(Properties ctx, int C_Country_ID);

	/**
	 * Return Countries as Array
	 *
	 * @param ctx
	 *            context
	 * @return countries
	 */
	public List<I_C_Country> getCountries(Properties ctx);

	List<I_C_Region> retrieveRegions(Properties ctx, int countryId);

	/**
	 * @param country
	 * @param orgId
	 * @param language
	 * @return country sequence or null
	 */
	I_C_Country_Sequence retrieveCountrySequence(I_C_Country country, int orgId, String language);

	I_C_Country retrieveCountryByCountryCode(String countryCode);

	int getCountryIdByCountryCode(String countryCode);

	String retrieveCountryCode2ByCountryId(int countryId);

	String retrieveCountryCode3ByCountryId(int countryId);

	ITranslatableString getCountryNameById(CountryId id);

	ITranslatableString getCountryNameById(int countryId);

}
