package com.example.lamivhan.model.course;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document("courses")
public class Course {

    @Field(name = "name")
    private String courseName;

    @Field(name = "level")
    private int difficultyLevel;
    @Field(name = "credits")
    private int credits;
    @Field(name = "study-time")
    private int recommendedStudyTime;

    public Course(String courseName, int difficultyLevel, int credits, int recommendedStudyTime) {
        this.courseName = courseName;
        this.difficultyLevel = difficultyLevel;
        this.credits = credits;
        this.recommendedStudyTime = recommendedStudyTime;
    }

    public String getCourseName() {
        return courseName;
    }

    public int getDifficultyLevel() {
        return difficultyLevel;
    }

    public int getCredits() {
        return credits;
    }

    public int getRecommendedStudyTime() {
        return recommendedStudyTime;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public void setDifficultyLevel(int difficultyLevel) {
        this.difficultyLevel = difficultyLevel;
    }

    public void setCredits(int credits) {
        this.credits = credits;
    }

    public void setRecommendedStudyTime(int recommendedStudyTime) {
        this.recommendedStudyTime = recommendedStudyTime;
    }
}