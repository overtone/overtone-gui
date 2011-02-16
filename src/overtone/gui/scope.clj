(ns
  ^{:doc "An oscilloscope style waveform viewer"
     :author "Jeff Rose & Sam Aaron"}
  overtone.gui.scope
  (:import
    (java.awt Graphics Dimension Color BasicStroke)
    (java.awt.geom Rectangle2D$Float Path2D$Float)
    (javax.swing JFrame JPanel))
  (:use
     [overtone event util time-utils]
     [overtone.sc core synth ugen buffer node]
    clojure.stacktrace)
  (:require [overtone.log :as log]
            [clojure.set :as set]))

(def SCOPE-BUF-SIZE 4096)
(def FPS 10)
(def scopes* (atom {}))
(def scope-pool* (atom nil))

(def WIDTH 600)
(def HEIGHT 400)
(def X-PADDING 5)
(def Y-PADDING 10)

; Some utility synths for signal routing and scoping
(defsynth bus->buf [bus 20 buf 0]
  (record-buf (in bus) buf))

(defsynth bus->bus [in-bus 20 out-bus 0]
  (out out-bus (in in-bus)))

(defn- update-scope [s]
  (let [{:keys [buf size width height panel y-array x-array panel]} s
        _ (println "buf:" buf)
        frames (buffer-data buf)
        step (int (/ (:size buf) width))
        y-scale (/ (- height (* 2 Y-PADDING)) -2)
        y-shift (+ (/ height 2) Y-PADDING)]
    (dotimes [x width]
      (aset ^ints y-array x
            (int (+ y-shift
                    (* y-scale
                       (aget ^floats frames (unchecked-multiply x step)))))))
    (.repaint panel)))

(defn- update-scopes []
  (doall (map update-scope (vals @scopes*))))

(defn- paint-scope [g id]
  (println "trying to paint" id )
  (println "scope: "  (get @scopes* id))
  (if-let [scope (get @scopes* id)]
    (let [{:keys [background width height color x-array y-array]} scope]
      (.setColor ^Graphics g ^Color background)
      (.fillRect ^Graphics g 0 0 width height)
      (.setColor ^Graphics g ^Color (Color. 100 100 100))
      (.drawRect ^Graphics g 0 0 width height)
      (.setColor ^Graphics g ^Color color)
      (.drawPolyline ^Graphics g ^ints x-array ^ints y-array width))))

(defn- wait-for-buffer [b]
  (loop [i 0]
    (cond
      (= 20 i) nil
      (not (buffer-ready? b)) (do
                                (java.lang.Thread/sleep 50)
                                (recur (inc i))))))

(def SCOPE-BUF-SIZE 4096)

(defn scope-panel [id width height]
  (let [panel (proxy [JPanel] [true]
                (paint [g] (paint-scope g id)))
        _ (.setPreferredSize panel (Dimension. width height))]
    panel))

(defn- scope-frame
  "Display scope window. If you specify keep-on-top to be true, the window will stay on top of the other windows in your environment."
  ([panel title keep-on-top width height]
     (let [f (JFrame. title)]
       (doto f
         (.setPreferredSize (Dimension. width height))
         (.add panel)
         (.pack)
         (.show)
         (.setAlwaysOnTop keep-on-top)))))

(defn- create-pool-if-necessary []
  (locking scope-pool*
    (if-not @scope-pool* (reset! scope-pool* (make-pool)))))


(defn- start-scopes-runner
  []
  (periodic update-scopes (/ 1000 FPS) 0 @scope-pool*))

(defn scopes-start
  []
  (create-pool-if-necessary)
  (start-scopes-runner))

;;  (.cancel (:runner @scope*) true)
(defn scopes-stop
  []
  (stop-and-reset-pool! scope-pool*))

(defn- scope-bus
  "Set a bus to view in the scope."
  [s]
  (let [buf (buffer SCOPE-BUF-SIZE)
        _ (wait-for-buffer buf)
        bus-synth (bus->buf :target 0 :position :tail (:num s) buf)]

    (apply-at (+ (now) 1000) update-scope [])
    (assoc s
      :size SCOPE-BUF-SIZE
      :bus-synth bus-synth
      :buf buf)))

(defn- scope-buf
  "Set a buffer to view in the scope."
  [s]
  (assoc s
    :size (:size (buffer-info (:num s)))
    :buf (:num s)))

(defn- mk-scope
  [num kind keep-on-top width height]
  (let [id      (str kind ": " num)
        panel   (scope-panel id width height)
        frame   (scope-frame panel id keep-on-top width height)
        x-array (int-array width)
        _x-init (dotimes [i width]
                  (aset x-array i i))
        y-array (int-array width)
        _y-init (dotimes [i width]
                  (aset y-array i (/ height 2)))
        scope   {:id id
                 :size 0
                 :num num
                 :panel panel
                 :kind kind
                 :color (Color. 0 130 226)
                 :background (Color. 50 50 50)
                 :frame frame
                 :width width
                 :height height
                 :x-array x-array
                 :y-array y-array}]
    (case kind
          :bus (scope-bus scope)
          :buf (scope-buf scope))))

(defn scope
  ([&{:keys [bus buf keep-on-top]
      :or {bus 0
           buf -1
           keep-on-top true}}]
     (let [kind (if (= -1 buf) :bus :buf)
           num  (if (= -1 buf) bus buf)
           s (mk-scope num kind keep-on-top WIDTH HEIGHT)]
       (swap! scopes* assoc (:id s) s)
       (start-scopes-runner))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
(require 'examples.basic)

(defonce test-frame (JFrame. "scope"))
(defonce _test-scope (do
             (.setPreferredSize test-panel (Dimension. 600 400))
             (.add (.getContentPane test-frame) test-panel)
             (.setScene test-panel (scope))
             (.pack test-frame)
             (.show test-frame)))

(defn- go-go-scope []
  (let [b (buffer 2048)]
    (Thread/sleep 100)
    (scope-buf b)
    (scope-on)
    (examples.basic/sizzle :bus 20)
    (bus->buf 20 (:id b))
    (bus->bus 20 0)))

(defn- spectrogram [in-bus]
  (let [fft-buf (buffer 2048)
        buf (buffer 2048)]
    (Thread/sleep 100)
    (freq-scope-zero in-bus fft-buf buf)))

(defn test-scope []
  (if (not (connected?))
    (do
      (boot)
      (on :examples-ready go-go-scope))
    (go-go-scope))
  (.show test-frame))
  )



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Spectragraph stuff to be worked on
(comment
  ; Note: The fft ugen writes into a buffer:
; dc, nyquist, real, imaginary, real, imaginary....
(comment defn- update-scope []
  (let [{:keys [buf width height panel]} @scope*
        frames  (buffer-data buf)
        n-reals (/ (- (:size buf) 2) 2)
        step    (int (/ n-reals width))
        y-scale (/ (- height (* 2 Y-PADDING)) 2)
        y-shift (+ (/ height 2) Y-PADDING)]
    (dotimes [x width]
      (aset ^ints y-array x
            (int (+ y-shift
                    (* y-scale
                       (aget ^floats frames
                             (+ 2 (* 2 (unchecked-multiply x step))))))))))
  (.repaint (:panel @scope*)))

(defsynth freq-scope-zero [in-bus 0 fft-buf 0 scope-buf 1
                           rate 4 phase 1 db-factor 0.02]
  (let [n-samples (* 0.5 (- (buf-samples:kr fft-buf) 2))
        signal (in in-bus)
        freqs  (fft fft-buf signal 0.75 :hann)
;        chain  (pv-mag-smear fft-buf 1)
        phasor (+ (+ n-samples 2)
                  (* n-samples
                     (lf-saw (/ rate (buf-dur:kr fft-buf)) phase)))
        phasor (round phasor 2)]
    (scope-out (* db-factor (ampdb (* 0.00285 (buf-rd 1 fft-buf phasor 1 1))))
               scope-buf)))


(defsynth freqs [in-bus 10 fft-buf 0]
  (let [n-samples (* 0.5 (- (buf-samples:kr fft-buf) 2))
        signal    (in in-bus 1)]
    (fft fft-buf signal 0.75 :hann)))



(defsynth scoper-outer [buf 0]
  (scope-out (sin-osc 200) buf))
(scope-out)

(defn freq-scope-buf [buf]
  )


)

