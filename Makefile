
N := oberlausitz-dresden
V := v2025.1
CRS := EPSG:25832

ifndef SUMO_HOME
	export SUMO_HOME := $(abspath ../../Sumo/)
endif

#define some important paths
# osmosis and sumo paths need to be in " because of blank space in path...
osmosis := "$(CURDIR)/../../../../../Program Files/osmosis-0.49.2//bin/osmosis.bat"
germany := $(CURDIR)/../../shared-svn/projects/matsim-germany
shared := $(CURDIR)/../../shared-svn/projects/matsim-oberlausitz-dresden
oberlausitz-dresden := $(CURDIR)/../../public-svn/matsim/scenarios/countries/de/oberlausitz-dresden/oberlausitz-dresden-$V/input/

MEMORY ?= 20G
JAR := matsim-$(N)-*.jar
NETWORK := $(germany)/maps/germany-250127.osm.pbf



# Scenario creation tool
sc := java -Xmx$(MEMORY) -jar $(JAR)

.PHONY: prepare

$(JAR):
	mvn package -DskipTests

# Required files
#this step is only necessary once. The downloaded network is uploaded to shared-svn/projects/matsim-germany/maps
#input/network.osm.pbf:
#	curl https://download.geofabrik.de/europe/germany-250127.osm.pbf\
#	  -o ../../shared-svn/projects/matsim-germany/maps/germany-250127.osm.pbf

#retrieve detailed network (see param highway) from OSM
input/network-detailed.osm.pbf: $(NETWORK)
	$(osmosis) --rb file=$<\
	 --tf accept-ways bicycle=yes highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential,unclassified,living_street\
	 --bounding-polygon file="$(shared)/data/dresden.poly"\
	 --used-node --wb $@

# 	This includes residential as well, since multiple cities are covered by the study area
#	retrieve coarse network (see param highway) from OSM
input/network-coarse.osm.pbf: $(NETWORK)
	$(osmosis) --rb file=$<\
	 --tf accept-ways highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary_link,secondary,tertiary,motorway_junction,residential\
	 --bounding-polygon file="$(shared)/data/oberlausitz.poly"\
	 --used-node --wb $@

  #	retrieve germany wide network (see param highway) from OSM
input/network-germany.osm.pbf: $(NETWORK)
	$(osmosis) --rb file=$<\
 	 --tf accept-ways highway=motorway,motorway_link,motorway_junction,trunk,trunk_link,primary,primary_link\
 	 --used-node --wb $@

input/network.osm: input/network-germany.osm.pbf input/network-coarse.osm.pbf input/network-detailed.osm.pbf
	$(osmosis) --rb file=$< --rb file=$(word 2,$^) --rb file=$(word 3,$^)\
  	 --merge --merge\
  	 --tag-transform file=input/remove-railway.xml\
  	 --wx $@

	rm ./input/network-detailed.osm.pbf
	rm ./input/network-coarse.osm.pbf
	rm ./input/network-germany.osm.pbf


	#	roadTypes are taken either from the general file "osmNetconvert.typ.xml"
	#	or from the german one "osmNetconvertUrbanDe.ty.xml"
input/sumo.net.xml: input/network.osm

	$(SUMO_HOME)/bin/netconvert --geometry.remove --ramps.guess --ramps.no-split\
	 --type-files $(SUMO_HOME)/data/typemap/osmNetconvert.typ.xml,$(SUMO_HOME)/data/typemap/osmNetconvertUrbanDe.typ.xml\
	 --tls.guess-signals true --tls.discard-simple --tls.join --tls.default-type actuated\
	 --junctions.join --junctions.corner-detail 5\
	 --roundabouts.guess --remove-edges.isolated\
	 --no-internal-links --keep-edges.by-vclass passenger,bicycle\
	 --remove-edges.by-vclass hov,tram,rail,rail_urban,rail_fast,pedestrian\
	 --output.original-names --output.street-names\
	 --proj "+proj=utm +zone=32 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs"\
	 --osm-files $< -o=$@

#TODO: continue here
input/$V/$N-$V-network.xml.gz: input/sumo.net.xml
	$(sc) prepare network-from-sumo $<\
	 --output $@

	# FIXME: Adjust

	$(sc) prepare network\
     --shp ../public-svn/matsim/scenarios/countries/de/$N/shp/prepare-network/av-and-drt-area.shp\
	 --network $@\
	 --output $@


input/$V/$N-$V-network-with-pt.xml.gz: input/$V/$N-$V-network.xml.gz
	# FIXME: Adjust GTFS

	$(sc) prepare transit-from-gtfs --network $<\
	 --output=input/$V\
	 --name $N-$V --date "2021-08-18" --target-crs $(CRS) \
	 ../shared-svn/projects/$N/data/20210816_regio.zip\
	 ../shared-svn/projects/$N/data/20210816_train_short.zip\
	 ../shared-svn/projects/$N/data/20210816_train_long.zip\
	 --prefix regio_,short_,long_\
	 --shp ../shared-svn/projects/$N/data/pt-area/pt-area.shp\
	 --shp ../shared-svn/projects/$N/data/Bayern.zip\
	 --shp ../shared-svn/projects/$N/data/germany-area/germany-area.shp\

input/freight-trips.xml.gz: input/$V/$N-$V-network.xml.gz
	# FIXME: Adjust path

	$(sc) extract-freight-trips ../shared-svn/projects/german-wide-freight/v1.2/german-wide-freight-25pct.xml.gz\
	 --network ../shared-svn/projects/german-wide-freight/original-data/german-primary-road.network.xml.gz\
	 --input-crs EPSG:5677\
	 --target-crs $(CRS)\
	 --shp ../shared-svn/projects/$N/data/shp/$N.shp --shp-crs $(CRS)\
	 --output $@

input/$V/prepare-100pct.plans.xml.gz:
	$(sc) prepare trajectory-to-plans\
	 --name prepare --sample-size 1 --output input/$V\
	 --population ../shared-svn/projects/matsim-$N/data/snz/20241129_Teilmodell_Hoyerswerda/Teilmodell/populationATA.xml.gz\
	 --attributes  ../shared-svn/projects/matsim-$N/data/snz/20241129_Teilmodell_Hoyerswerda/Teilmodell/personAttributesATA.xml.gz

#	$(sc) prepare resolve-grid-coords\
#	 input/$V/prepare-25pct.plans.xml.gz\
#	 --input-crs $(CRS)\
#	 --grid-resolution 300\
#	 --landuse ../matsim-leipzig/scenarios/input/landuse/landuse.shp\
#	 --output $@

input/$V/$N-$V-100pct.plans-initial.xml.gz: input/$V/prepare-100pct.plans.xml.gz

	# Use direct input for now
	cp $< $@

#	$(sc) prepare generate-short-distance-trips\
# 	 --population input/$V/prepare-25pct.plans.xml.gz\
# 	 --input-crs $(CRS)\
#	 --shp ../shared-svn/projects/$N/data/shp/$N.shp --shp-crs $(CRS)\
# 	 --num-trips 111111 # FIXME

#	$(sc) prepare adjust-activity-to-link-distances input/$V/prepare-25pct.plans-with-trips.xml.gz\
#	 --shp ../shared-svn/projects/$N/data/shp/$N.shp --shp-crs $(CRS)\
#     --scale 1.15\
#     --input-crs $(CRS)\
#     --network input/$V/$N-$V-network.xml.gz\
#     --output input/$V/prepare-25pct.plans-adj.xml.gz

#	$(sc) prepare xy-to-links --network input/$V/$N-$V-network.xml.gz --input input/$V/prepare-25pct.plans-adj.xml.gz --output $@

#	$(sc) prepare fix-subtour-modes --input $@ --output $@

#	$(sc) prepare merge-populations $@ $< --output $@

#	$(sc) prepare extract-home-coordinates $@ --csv input/$V/$N-$V-homes.csv

#	$(sc) prepare downsample-population $@\
#    	 --sample-size 0.25\
#    	 --samples 0.1 0.01\


check: input/$V/$N-$V-100pct.plans-initial.xml.gz
	$(sc) analysis check-population $<\
 	 --input-crs $(CRS)\
	 --shp ../shared-svn/projects/matsim-$N/data/snz/20241129_Teilmodell_Hoyerswerda/Teilmodell/UG.shp/UG.shp --shp-crs $(CRS)

# Aggregated target
prepare: input/$V/$N-$V-100pct.plans-initial.xml.gz input/$V/$N-$V-network-with-pt.xml.gz
	echo "Done"