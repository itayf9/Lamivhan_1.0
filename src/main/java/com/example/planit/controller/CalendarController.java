package com.example.planit.controller;

import com.example.planit.engine.CalendarEngine;
import com.example.planit.engine.HolidaysEngine;
import com.example.planit.model.exam.Exam;
import com.example.planit.model.mongo.course.CoursesRepository;
import com.example.planit.model.mongo.user.User;
import com.example.planit.model.mongo.user.UserRepository;
import com.example.planit.utill.Constants;
import com.example.planit.utill.dto.DTOscanResponseToClient;
import com.example.planit.utill.dto.DTOstatus;
import com.example.planit.utill.dto.DTOuserCalendarsInformation;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.services.calendar.model.Event;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.example.planit.utill.Constants.ISRAEL_HOLIDAYS_CODE;

@CrossOrigin(origins = "http://localhost:3000")
@RestController
public class CalendarController {

    @Autowired
    private Environment env;

    @Autowired
    private CoursesRepository courseRepo;

    @Autowired
    private UserRepository userRepo;

    private String CLIENT_ID;
    private String CLIENT_SECRET;

    private Set<String> holidaysDatesCurrentYear;
    private Set<String> holidaysDatesNextYear;

    @PostConstruct
    private void init() {

        CLIENT_ID = env.getProperty("spring.security.oauth2.client.registration.google.client-id");
        CLIENT_SECRET = env.getProperty("spring.security.oauth2.client.registration.google.client-secret");

        // extract the holidays dates as iso format and return it in a set of string(iso format) (for current year and the next year).
        holidaysDatesCurrentYear = HolidaysEngine.getDatesOfHolidays(env.getProperty("holidays_api_key"), ISRAEL_HOLIDAYS_CODE, ZonedDateTime.now().getYear());
        holidaysDatesNextYear = HolidaysEngine.getDatesOfHolidays(env.getProperty("holidays_api_key"), ISRAEL_HOLIDAYS_CODE, ZonedDateTime.now().getYear() + 1);
    }

    /**
     * Scan the user's Calendar to get list of events and check to see if user has fullDayEvents existed.
     *
     * @param email user's email address to search the User on DB & get preferences.
     * @return ResponseEntity<List < Event>> we return list of events in a case of full day events found, otherwise we generate the calendar.
     * @throws IOException              IOException
     * @throws GeneralSecurityException GeneralSecurityException
     */
    @PostMapping(value = "/scan")
    public ResponseEntity<DTOscanResponseToClient> scanUserEvents(@RequestParam String email, @RequestParam String start, @RequestParam String end) throws IOException, GeneralSecurityException {

        long s = System.currentTimeMillis();

        //CalendarEngine.scan

        // check if user exist in DB
        Optional<User> maybeUser = userRepo.findUserByEmail(email);
        if (maybeUser.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        // get instance of the user
        User user = maybeUser.get();

        // check if accessToken is already invalid
        validateAccessToken(user);

        // 1# get List of user's events
        // perform a scan on the user's Calendar to get all of his events at the time interval
        DTOuserCalendarsInformation userEvents = CalendarEngine.getUserCalendarsInformation(user.getAccessToken(), user.getExpireTimeInMilliseconds(), start, end, courseRepo);

        // fullDayEvents - a list of events that represents the user's full day events
        List<Event> fullDayEvents = userEvents.getFullDayEvents();

        // events - a list of events that represents all the user's events
        // planItCalendarOldEvents - a list of PlanIt calendar old events
        List<Event> events = userEvents.getEvents();
        List<Event> planItCalendarOldEvents = userEvents.getPlanItCalendarOldEvents();
        List<Exam> examsFound = userEvents.getExamsFound();

        // checks if no exams are
        if (examsFound.size() <= 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new DTOscanResponseToClient(false, Constants.ERROR_NO_EXAMS_FOUND, fullDayEvents));
        }


        if (fullDayEvents.size() != 0) {

            fullDayEvents = CalendarEngine.handleHolidaysInFullDaysEvents(fullDayEvents, events
                    , user.getUserPreferences().isStudyOnHolyDays(), holidaysDatesCurrentYear, holidaysDatesNextYear);

            // after we delete all the event we can. we send the rest of the fullDayEvents we don`t know how to handle.
            if (fullDayEvents.size() != 0) {

                // return the user with the updated list of fullDayEvents.
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new DTOscanResponseToClient(false, Constants.UNHANDLED_FULL_DAY_EVENTS, fullDayEvents));
            }

        }

        CalendarEngine.generatePlanItCalendar(events, userEvents.getExamsFound(), maybeUser.get(), userEvents.getCalendarService(), userRepo, start, planItCalendarOldEvents);

        long t = System.currentTimeMillis();
        System.out.println(t - s + " ms");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new DTOscanResponseToClient(true, Constants.NO_PROBLEM, new ArrayList<>()));
    }

    /**
     * this endpoint re-scan the user Calendar events, deals with the full days events that has been found and generates the plan it calendar.
     *
     * @param email         user's email address to search the User on DB & get preferences
     * @param userDecisions array of boolean values representing
     * @return ResponseEntity<String> this method not suppose to fail unless it's been called externally
     * @throws IOException              IOException
     * @throws GeneralSecurityException GeneralSecurityException
     */
    @PostMapping(value = "/generate", consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<DTOstatus> generateStudyEvents(@RequestParam String email, @RequestParam String start, @RequestParam String end, @RequestBody boolean[] userDecisions) throws IOException, GeneralSecurityException {

        // check if user exist in DB
        Optional<User> maybeUser = userRepo.findUserByEmail(email);
        if (maybeUser.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        // get instance of the user
        User user = maybeUser.get();

        // check if accessToken is already invalid
        validateAccessToken(user);

        // 1# get List of user's events
        // perform a scan on the user's Calendar to get all of his events at the time interval
        DTOuserCalendarsInformation userEvents = CalendarEngine.getUserCalendarsInformation(user.getAccessToken(), user.getExpireTimeInMilliseconds(), start, end, courseRepo);

        // fullDayEvents - a list of events that represents the user's full day events
        List<Event> fullDayEvents = userEvents.getFullDayEvents();

        // planItCalendarOldEvents - a list of PlanIt calendar old events
        List<Event> planItCalendarOldEvents = userEvents.getPlanItCalendarOldEvents();

        // events - a list of events that represents all the user's events
        List<Event> events = userEvents.getEvents();

        // check if fullDayEvents List is empty (which doesn't suppose to be)
        if (fullDayEvents.size() != 0) {

            fullDayEvents = CalendarEngine.handleHolidaysInFullDaysEvents(fullDayEvents, events
                    , user.getUserPreferences().isStudyOnHolyDays(), holidaysDatesCurrentYear, holidaysDatesNextYear);

            // go through the list
            for (int i = 0; i < fullDayEvents.size(); i++) {

                boolean userWantToStudyAtCurrentFullDayEvent = userDecisions[i];
                Event currentFullDayEvent = fullDayEvents.get(i);

                // check if user want to study at the current fullDayEvent
                if (userWantToStudyAtCurrentFullDayEvent) {
                    events.remove(currentFullDayEvent); // remove event element from the list of all events.
                }
            }
        }

        // 2# 3# 4# 5#
        CalendarEngine.generatePlanItCalendar(events, userEvents.getExamsFound(), maybeUser.get(), userEvents.getCalendarService(), userRepo, start, planItCalendarOldEvents);

        return ResponseEntity.status(HttpStatus.CREATED).body(new DTOstatus(true, Constants.NO_PROBLEM));
    }

    /**
     * checks the access token of the user.
     * if not valid, refreshes the access token
     * also, updates the user's new access token in the DB
     *
     * @param user a {@link User} represents the user
     * @throws IOException              IOException
     * @throws GeneralSecurityException GeneralSecurityException
     */
    private void validateAccessToken(User user) throws IOException, GeneralSecurityException {

        // checks if the access token is not valid yet
        if (!CalendarEngine.isAccessTokenValid(user.getExpireTimeInMilliseconds())) {

            // refresh the accessToken
            TokenResponse tokensResponse = CalendarEngine.refreshAccessToken(user.getRefreshToken(), CLIENT_ID, CLIENT_SECRET);
            long expireTimeInMilliseconds = Instant.now().plusMillis(((tokensResponse.getExpiresInSeconds() - 100) * 1000)).toEpochMilli();

            // updates the access token of the user in the DB
            user.setAccessToken(tokensResponse.getAccessToken());
            user.setExpireTimeInMilliseconds(expireTimeInMilliseconds);
            userRepo.save(user);
        }
    }
}