#!/usr/bin/env python
# -*- coding: utf-8 -*-

import geopandas as gpd
import numpy as np
import pandas as pd
from matsim.scenariogen.data import run_create_ref_data
from matsim.scenariogen.data.preparation import calc_needed_short_distance_trips, cut

CRS = "EPSG:25832"


def person_filter(df):
    """ Filter person that are relevant for the calibration."""

    df = gpd.GeoDataFrame(df, geometry=gpd.GeoSeries.from_wkt(df.geom, crs="EPSG:4326").to_crs(CRS))
    df = gpd.sjoin(df, region, how="inner", predicate="intersects")

    # Groups will be shown on the dashboard
    df["age"] = cut(df.age, [0, 12, 18, 25, 35, 66, np.inf])

    # Only weekdays Monday through Thursday are considered, with persons present in their home region
    return df[df.present_on_day & (df.reporting_day <= 4)]


def trip_filter(df):
    # All modes, expect for "other" are considered
    return df[df.main_mode != "other"]


if __name__ == "__main__":

    # Defines the study area
    region = gpd.read_file("../../../../shared-svn/projects/matsim-oberlausitz-dresden/data/snz/20241129_Teilmodell_Hoyerswerda/Teilmodell/UG.shp").to_crs(CRS)

    # This contains the path to the MiD 2017 data with the highest resolution
    # See https://daten.clearingstelle-verkehr.de/279/ for more information, the data is not included in this repository
    r = run_create_ref_data.create(
        # this is the MID2017 dataset, it is not available in svn as it has to be encrypted
        "/Volumes/Untitled/B3_Lokal-Datensatzpaket/CSV",
        person_filter, trip_filter,
        run_create_ref_data.InvalidHandling.REMOVE_TRIPS,
        ref_groups=["age", "economic_status"]
    )

    print("Filtered %s persons" % len(r.persons))
    print("Filtered %s trips" % len(r.trips))

    print(r.share)

    print(r.trips.groupby("dist_group").agg(n=("main_mode", "count")) / len(r.trips))
