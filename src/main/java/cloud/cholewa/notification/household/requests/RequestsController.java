package cloud.cholewa.notification.household.requests;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The SMS of the household requests by hand (HAS-410) - the check of the minute after a deploy, next to
 * {@code GET /skippy} and like it deliberately not routed by the gateway (a port-forward reaches it). Runs the
 * pass of 08:00 once and answers 202 with what it did; it texts every member with a pending request.
 */
@RestController
@RequiredArgsConstructor
class RequestsController {

    private final RequestsSmsPass pass;
    private final RequestsReminders reminders;

    @PostMapping("requests/send")
    Mono<ResponseEntity<RequestsSmsPass.Outcome>> send() {
        return pass.run().map(outcome -> ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome));
    }

    //HAS-416: the announcements of the requests ticked off, as every minute - it tells the authors waiting
    @PostMapping("requests/announce")
    Mono<ResponseEntity<RequestsReminders.Outcome>> announce() {
        return reminders.announceDone().map(outcome -> ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome));
    }

    //HAS-416: the evening pass, as at 20:00 - it reminds every recipient not reminded today and tells the authors
    //of the requests still open
    @PostMapping("requests/remind")
    Mono<ResponseEntity<RequestsReminders.Outcome>> remind() {
        return reminders.evening().map(outcome -> ResponseEntity.status(HttpStatus.ACCEPTED).body(outcome));
    }
}
