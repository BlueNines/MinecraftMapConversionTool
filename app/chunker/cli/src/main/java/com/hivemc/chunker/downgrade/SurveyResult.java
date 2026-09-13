package com.hivemc.chunker.downgrade;

/**
 * The outcome of a survey pass: how far the world has to move, and what that move costs.
 *
 * @param lowestBlockY        the lowest block Y found in the source world.
 * @param lowestSectionY      the lowest section index found (which may be lower still in empty space).
 * @param highestSectionY     the highest section index found.
 * @param nonEmptySections    how many sections actually held blocks.
 * @param shiftY              how far every Y co-ordinate is moved. Always a whole number of sections.
 * @param clippedSections     how many sections the shift pushes past the output ceiling.
 * @param clipped             whether anything is lost to the ceiling at all.
 */
public record SurveyResult(
        int lowestBlockY,
        int lowestSectionY,
        int highestSectionY,
        int nonEmptySections,
        int shiftY,
        int clippedSections,
        boolean clipped
) {
    /**
     * Whether a shift is actually required.
     *
     * @return true if the world needs to be moved upwards.
     */
    public boolean requiresShift() {
        return shiftY != 0;
    }

    /**
     * The shift expressed in whole sections.
     *
     * @return the number of sections to move every chunk up by.
     */
    public int shiftSections() {
        return shiftY / BlockSurvey.SECTION_SIZE;
    }

    /**
     * The lowest Y this world will occupy once shifted.
     *
     * @return the projected lowest block Y.
     */
    public int projectedLowestY() {
        return lowestBlockY + shiftY;
    }

    /**
     * The highest Y this world will occupy once shifted, before any clipping.
     *
     * @return the projected highest block Y.
     */
    public int projectedHighestY() {
        return highestSectionY * BlockSurvey.SECTION_SIZE + BlockSurvey.SECTION_SIZE - 1 + shiftY;
    }
}
