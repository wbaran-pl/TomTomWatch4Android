/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.studioblueplanet.tomtomwatch;

/**
 * This class represents the map function, showing a map with a route.
 * @author jorgen
 */
public abstract class Map
{

   /**
     * This method show the track in this frame on a google map
     * @param activityData The activity data structure containing the track (Activity) to show
     * @return A string indicating the result of the showing (ToDo: remove or make sensible value).
     */
    public abstract String showTrack(ActivityData activityData);

    /**
     * This method show the route track in this frame on a google map
     * @param route The route to show
     * @return A string indicating the result of the showing (ToDo: remove or make sensible value).
     */
    public abstract String showTrack(Route route);

    /**
     * Hides the track
     */
    public abstract void hideTrack();
    
}
