(ns snowball.comprehension
  (:require [clojure.core.async :as a]
            [taoensso.timbre :as log]
            [bounce.system :as b]
            [snowball.discord :as discord]
            [snowball.config :as config]
            [snowball.stream :as stream])
  (:import [snowball.porcupine Porcupine]))

(b/defcomponent phrase-chan {:bounce/deps #{discord/audio-chan config/value}}
  (log/info "Starting phrase channel")
  (let [phrase-chan (a/chan 100)
        state! (atom {})
        loop-chan (a/go-loop []
                    ;; Pull a single audio packet out of Discord.
                    ;; We receive one every 20ms and know which user it came from.
                    (when-let [{:keys [user audio]} (a/<! discord/audio-chan)]
                      (try 
                        ;; Update that particular user's state.
                        (swap! state! update user
                               (fnil (fn [{:keys [byte-stream debounce] :as phrase}]
                                       ;; Kill any existing future.
                                       (future-cancel debounce)

                                       ;; Don't allow any more future refreshing if the vector gets too long.
                                       ;; This would be from someone playing music / talking for over a minute etc.
                                       (when (< (stream/size byte-stream) (get-in config/value [:comprehension :stream-bytes-cutoff]))
                                         ;; Add the new section of audio to the byte stream and create another future.
                                         (stream/write byte-stream audio)
                                         (assoc phrase :debounce
                                                (future
                                                  ;; The future waits a chunk of time before continuing, gives time for a future-cancel.
                                                  (Thread/sleep (get-in config/value [:comprehension :phrase-debounce-ms]))

                                                  ;; Put the phrase on the channel and remove it from the state.
                                                  (a/>! phrase-chan (@state! user))
                                                  (swap! state! dissoc user)))))
                                     {:byte-stream (stream/byte-array-output)
                                      :debounce (future)}))
                        (catch Exception e
                          (log/error "Caught error in phrase-chan loop" (Throwable->map e))))
                      (recur)))]

    (b/with-stop phrase-chan
      (log/info "Closing phrase channel")
      (a/close! loop-chan)
      (doseq [{:keys [debounce]} (vals @state!)]
        (future-cancel debounce))
      (a/close! phrase-chan))))

(b/defcomponent woken-by-chan {:bounce/deps #{phrase-chan}}
  (log/info "Starting Porcupine")
  (let [woken-by-chan (a/chan)
        porcupine (Porcupine. "wake-word-engine/Porcupine/lib/common/porcupine_params.pv"
                              "wake-word-engine/hey snowball_linux.ppn"
                              0.5)]
    (log/info (str "Porcupine frame length is " (.getFrameLength porcupine) " samples, "
                   "sample rate is " (.getSampleRate porcupine) "hz."))
    (b/with-stop woken-by-chan
      (log/info "Shutting down Porcupine")
      (.delete porcupine))))