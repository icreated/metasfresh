package de.metas.fresh.ordercheckup.impl;

/*
 * #%L
 * de.metas.fresh.base
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


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

import org.adempiere.ad.model.util.ModelByIdComparator;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.ISysConfigBL;
import org.adempiere.util.Services;
import org.adempiere.warehouse.model.I_M_Warehouse;
import org.compiere.model.I_C_Order;
import org.compiere.model.I_S_Resource;
import org.compiere.util.CLogger;
import org.compiere.util.Util;
import org.compiere.util.Util.ArrayKey;
import org.eevolution.model.I_PP_Product_Planning;

import de.metas.adempiere.service.IOrderDAO;
import de.metas.fresh.model.I_C_Order_MFGWarehouse_Report;
import de.metas.fresh.model.X_C_Order_MFGWarehouse_Report;
import de.metas.fresh.ordercheckup.IOrderCheckupBL;
import de.metas.fresh.ordercheckup.IOrderCheckupDAO;
import de.metas.fresh.ordercheckup.model.I_C_BPartner;
import de.metas.handlingunits.model.I_C_OrderLine;

/**
 *
 * @author ts
 * @task http://dewiki908/mediawiki/index.php/09028_Produktionsauftrag-Bestellkontrolle_automatisch_ausdrucken_%28106402701484%29
 */
public class OrderCheckupBL implements IOrderCheckupBL
{
	private static final transient CLogger logger = CLogger.getCLogger(OrderCheckupBL.class);

	private static final String SYSCONFIG_ORDERCHECKUP_CREATE_AND_ROUTE_JASPER_REPORTS_ON_SALES_ORDER_COMPLETE = "de.metas.fresh.ordercheckup.CreateAndRouteJasperReports.OnSalesOrderComplete";

	@Override
	public void generateReportsIfEligible(final I_C_Order order)
	{
		// Make sure the order is eligible for reporting
		if (!isEligibleForReporting(order))
		{
			return;
		}

		//
		// Void all previous reports, because we will generate them again.
		voidReports(order);

		// services
		final IOrderCheckupDAO orderCheckupDAO = Services.get(IOrderCheckupDAO.class);
		final IOrderDAO orderDAO = Services.get(IOrderDAO.class);

		//
		// Iterate all order lines and those lines to corresponding "per Warehouse" reports.
		final Set<I_S_Resource> plants = new TreeSet<>(ModelByIdComparator.getInstance());
		final Map<ArrayKey, OrderCheckupBuilder> reportBuilders = new HashMap<>();
		final List<I_C_OrderLine> orderLines = orderDAO.retrieveOrderLines(order, I_C_OrderLine.class);
		for (final I_C_OrderLine orderLine : orderLines)
		{
			//
			// Retrieve the product data planning which defines how the order line product will be manufactured
			final I_PP_Product_Planning mfgProductPlanning = orderCheckupDAO.retrieveManufacturingProductPlanningOrNull(orderLine);
			if (mfgProductPlanning == null)
			{
				logger.log(Level.INFO, "Skip order line because no manufacturing product planning was found for it: {0}", orderLine);
				continue;
			}

			//
			// Retrieve the manufacturing warehouse
			final I_M_Warehouse mfgWarehouse = InterfaceWrapperHelper.create(mfgProductPlanning.getM_Warehouse(), I_M_Warehouse.class);
			if (mfgWarehouse == null || mfgWarehouse.getM_Warehouse_ID() <= 0)
			{
				logger.log(Level.INFO, "Skip order line because no manufacturing warehouse was found for it: {0}", orderLine);
				continue;
			}

			final I_S_Resource plant = mfgProductPlanning.getS_Resource();

			//
			// Add order line to per Manufacturing warehouse report
			{
				final String documentType = X_C_Order_MFGWarehouse_Report.DOCUMENTTYPE_Warehouse;
				final ArrayKey reportBuilderKey = Util.mkKey(order.getC_Order_ID(), documentType, mfgWarehouse.getM_Warehouse_ID());
				OrderCheckupBuilder reportBuilder = reportBuilders.get(reportBuilderKey);
				if (reportBuilder == null)
				{
					reportBuilder = OrderCheckupBuilder.newBuilder()
							.setC_Order(order)
							.setDocumentType(documentType)
							.setM_Warehouse(mfgWarehouse)
							.setPP_Plant(plant)
							.setReponsibleUser(mfgWarehouse.getAD_User());
					reportBuilders.put(reportBuilderKey, reportBuilder);
				}

				reportBuilder.addOrderLine(orderLine);
			}

			//
			// Add order line to per Plant report
			if (plant != null)
			{
				plants.add(plant);

				// NOTE: instead of creating a plant report only with the products for that plant,
				// we will create a report will all order lines, for each plant (per Torby via Mark requirements).
				// final String documentType = X_C_Order_MFGWarehouse_Report.DOCUMENTTYPE_Plant;
				// final ArrayKey reportBuilderKey = Util.mkKey(order.getC_Order_ID(), documentType, plant.getS_Resource_ID());
				// OrderCheckupBuilder reportBuilder = reportBuilders.get(reportBuilderKey);
				// if (reportBuilder == null)
				// {
				// reportBuilder = OrderCheckupBuilder.newBuilder()
				// .setC_Order(order)
				// .setDocumentType(documentType)
				// .setM_Warehouse(null) // no warehouse because we are aggregating on plant level
				// .setPP_Plant(plant)
				// .setReponsibleUser(plant.getAD_User());
				// reportBuilders.put(reportBuilderKey, reportBuilder);
				// }
				//
				// reportBuilder.addOrderLine(orderLine);
			}
		}
		//
		// Iterate all created report builders and actually build them
		for (final OrderCheckupBuilder reportBuilder : reportBuilders.values())
		{
			reportBuilder.build();
		}

		//
		// Create the reports for plant managers
		for (final I_S_Resource plant : plants)
		{
			final OrderCheckupBuilder reportBuilder = OrderCheckupBuilder.newBuilder()
					.setC_Order(order)
					.setDocumentType(X_C_Order_MFGWarehouse_Report.DOCUMENTTYPE_Plant)
					.setM_Warehouse(null) // no warehouse because we are aggregating on plant level
					.setPP_Plant(plant)
					.setReponsibleUser(plant.getAD_User());
			for (final I_C_OrderLine orderLine : orderLines)
			{
				// Don't add the packing materials
				if (orderLine.isPackagingMaterial())
				{
					continue;
				}

				reportBuilder.addOrderLine(orderLine);
			}

			reportBuilder.build();
		}

	}

	@Override
	public boolean isEligibleForReporting(final I_C_Order order)
	{
		if (!order.isSOTrx())
		{
			logger.log(Level.FINE, "C_Order_ID {0} is not a sales order; nothing to do", order.getC_Order_ID());
			return false; // nothing to do
		}

		// NOTE: don't check the DocStatus because if the method is called from AFTER_COMPLETE, the status is not set yet.
		// More, it does not matter, because we could report anytime.

		return true;
	}

	@Override
	public final boolean isGenerateReportsOnOrderComplete(final I_C_Order order)
	{
		if (!isEligibleForReporting(order))
		{
			return false; // nothing to do; log messages were already created in isEligibleForReporting
		}
		
		final ISysConfigBL sysConfigBL = Services.get(ISysConfigBL.class);
		final boolean sysConfigValueIsTrue = sysConfigBL.getBooleanValue(
				SYSCONFIG_ORDERCHECKUP_CREATE_AND_ROUTE_JASPER_REPORTS_ON_SALES_ORDER_COMPLETE,
				false, // by default, do nothing. This needs to set up and tested by the customer to make sense
				order.getAD_Client_ID(),
				order.getAD_Org_ID());

		if (!sysConfigValueIsTrue)
		{
			logger.log(Level.FINE, "AD_SysConfig {0} is *not* set to 'Y' for AD_Client_ID={1} and AD_Org_ID={2}; nothing to do for C_Order_ID {3}.",
					new Object[] {
							SYSCONFIG_ORDERCHECKUP_CREATE_AND_ROUTE_JASPER_REPORTS_ON_SALES_ORDER_COMPLETE,
							order.getAD_Client_ID(),
							order.getAD_Org_ID(),
							order.getC_Order_ID() });
			return false; // nothing to do
		}
		final I_C_BPartner bpartner = InterfaceWrapperHelper.create(order.getC_BPartner(), I_C_BPartner.class);
		if (bpartner.isDisableOrderCheckup())
		{
			logger.log(Level.FINE, "C_BPartner {0} has IsDisableOrderCheckup='Y'; nothing to do for C_Order_ID {1}.",
					new Object[] {
							bpartner.getValue(),
							order.getC_Order_ID()
					});
			return false; // nothing to do
		}

		return true;
	}

	@Override
	public void voidReports(final I_C_Order order)
	{
		final List<I_C_Order_MFGWarehouse_Report> reports = Services.get(IOrderCheckupDAO.class).retrieveAllReports(order);
		for (final I_C_Order_MFGWarehouse_Report report : reports)
		{
			report.setIsActive(false);
			InterfaceWrapperHelper.save(report);
		}
	}
}
