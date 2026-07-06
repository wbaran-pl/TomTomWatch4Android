/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.studioblueplanet.tomtomwatch;

import android.graphics.Color;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;

import net.studioblueplanet.ttbin.Activity;
import net.studioblueplanet.ttbin.ActivityRecord;

/**
 * Android implementation of the Map class using osmdroid.
 * @author AI
 */
public class MapOsmDroid extends Map {
    private final MapView mapView;
    private final List<Polyline> trackLines;
    private final List<Marker> waypoints;

    /**
     * Constructor
     * @param mapView The osmdroid MapView to display the track on
     */
    public MapOsmDroid(MapView mapView) {
        this.mapView = mapView;
        this.trackLines = new ArrayList<>();
        this.waypoints = new ArrayList<>();

        // Basic map configuration
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);
    }

    private void clearMap() {
        for (Polyline line : trackLines) {
            mapView.getOverlayManager().remove(line);
        }
        trackLines.clear();
        for (Marker marker : waypoints) {
            mapView.getOverlayManager().remove(marker);
        }
        waypoints.clear();
    }

    private void updateMap(List<List<GeoPoint>> track, List<GeoPoint> waypointPositions) {
        clearMap();

        List<GeoPoint> allPoints = new ArrayList<>();
        int segmentCount = 0;

        for (List<GeoPoint> segment : track) {
            Polyline line = new Polyline();
            line.setPoints(segment);
            // Alternate colors for segments like the original code
            line.getOutlinePaint().setColor(segmentCount % 2 == 0 ? Color.RED : Color.BLUE);
            line.getOutlinePaint().setStrokeWidth(5f);

            mapView.getOverlayManager().add(line);
            trackLines.add(line);
            allPoints.addAll(segment);
            segmentCount++;
        }

        for (GeoPoint pos : waypointPositions) {
            Marker marker = new Marker(mapView);
            marker.setPosition(pos);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            mapView.getOverlayManager().add(marker);
            waypoints.add(marker);
        }

        if (!allPoints.isEmpty()) {
            zoomToFit(allPoints);
        }

        mapView.invalidate();
    }

    private void zoomToFit(List<GeoPoint> points) {
        if (points.isEmpty()) return;

        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (GeoPoint point : points) {
            minLat = Math.min(minLat, point.getLatitude());
            maxLat = Math.max(maxLat, point.getLatitude());
            minLon = Math.min(minLon, point.getLongitude());
            maxLon = Math.max(maxLon, point.getLongitude());
        }

        BoundingBox bbox = new BoundingBox(maxLat, maxLon, minLat, minLon);
        mapView.zoomToBoundingBox(bbox, true, 100);
    }

    @Override
    public String showTrack(ActivityData activityData) {
        Activity activity = activityData.activity;
        List<List<GeoPoint>> track = new ArrayList<>();
        List<GeoPoint> waypointPositions = new ArrayList<>();

        int numOfSegments = activity.getNumberOfSegments();
        for (int i = 0; i < numOfSegments; i++) {
            List<ActivityRecord> records = activity.getRecords(i);
            List<GeoPoint> segmentPoints = new ArrayList<>();
            boolean firstInSegment = true;

            for (ActivityRecord record : records) {
                double lat = record.getLatitude();
                double lon = record.getLongitude();

                if (lat != 0.0 && lon != 0.0) {
                    GeoPoint gp = new GeoPoint(lat, lon);
                    segmentPoints.add(gp);
                    if (firstInSegment) {
                        waypointPositions.add(gp);
                        firstInSegment = false;
                    }
                }
            }
            if (!segmentPoints.isEmpty()) {
                track.add(segmentPoints);
                // Add last point as waypoint if it was the last segment
                if (i == numOfSegments - 1) {
                    waypointPositions.add(segmentPoints.get(segmentPoints.size() - 1));
                }
            }
        }

        if (track.isEmpty()) {
            hideTrack();
        } else {
            updateMap(track, waypointPositions);
        }
        return "";
    }

    @Override
    public String showTrack(Route route) {
        List<List<GeoPoint>> track = new ArrayList<>();
        List<GeoPoint> waypointPositions = new ArrayList<>();

        int numOfSegments = route.getNumberOfSegments();
        for (int i = 0; i < numOfSegments; i++) {
            RouteSegment segment = route.getSegment(i);
            List<GeoPoint> segmentPoints = new ArrayList<>();
            int numOfPoints = segment.getNumberOfPoints();

            for (int j = 0; j < numOfPoints; j++) {
                RoutePoint point = segment.getPoint(j);
                GeoPoint gp = new GeoPoint(point.getLatitude(), point.getLongitude());
                segmentPoints.add(gp);
                if (i == 0 && j == 0) {
                    waypointPositions.add(gp);
                }
            }
            if (!segmentPoints.isEmpty()) {
                track.add(segmentPoints);
                if (i == numOfSegments - 1) {
                    waypointPositions.add(segmentPoints.get(segmentPoints.size() - 1));
                }
            }
        }

        if (track.isEmpty()) {
            hideTrack();
        } else {
            updateMap(track, waypointPositions);
        }
        return "";
    }

    @Override
    public void hideTrack() {
        clearMap();
        mapView.getController().setCenter(new GeoPoint(53.252, 6.588));
        mapView.getController().setZoom(15.0);
        mapView.invalidate();
    }
}
