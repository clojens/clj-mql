# What is it? #

`clj-mql` is a client library for Freebase (or any site that implements its API).

It currently allows you to issue unauthenticated version, status, read, search,
and reconcile requests, and to issue write requests within the scope of a
login. Logins and writes are performed over HTTPS by default.

This project has a dependency on clj-apache-http, as well as Clojure and
clojure-contrib, and Dan Larkin's JSON library.

A recent build of clj-apache-http and clojure-json are in the lib/ directory;
everything you need to use this code (apart from Clojure and contrib) is in the
checkout.

Also included in this library is an example application which lists restaurants
in a city.

You can use the enclosed shell scripts as an example:

    $ sh restaurants.sh "/en/los_gatos"
    Looking in /en/los_gatos ...
    Andale Taqueria,  21 N  Santa Cruz Ave
    Cafe Primavera,  15970 Los Gatos Blvd
    California Cafe Bar and Grill,  50 University Avenue
    Fleur de Cocoa,  39 N. Santa Cruz Ave
    Gallo's,  14180 Blossom Hill Road
    High Tech Burrito,  15960 Los Gatos Boulevard
    Kamakura Japanese Restaurant,  135 N. Santa Cruz Avenue
    Los Gatos Brewing Company,  130 N. Santa Cruz Avenue
    Manresa,  320 Village Lane
    Marbella,  14109 Winchester Boulevard
    Nonno's Pizza and Pasta,  24133 Broadway
    Pastaria and Market,  49 E Main St
    Ristorante Valeriano,  160 W Main St
    T-Birds Pizza of Las Gatos,  444 N. Santa Cruz Avenue
    Tapestry: A California Bistro,  11 College Avenue
    Valeriano's Ristorante,  160 W. Main St.
    Whole Foods,  15980
    Willow Street Wood-Fired Pizza,  20 S. Santa Cruz Avenue
    Wine Cellar,  50 University Ave.
    Cin-Cin Wine Bar and Restaurant,  386 Village Lane
    Pedro's,  316 North Santa Cruz Aveue


# Examples #

    (use 'com.twinql.clojure.mql)

    (with-sandbox                    ; Use Sandbox API locations.
      (with-login ["user" "pass"]    ; Make a login request and keep the cookie.
        (println "Logged in?" (mql-logged-in?)))

      ;; Ordinary requests don't need authentication; only writes.
      (prn (take 3 (map :id (mql-search "Los Gatos")))))

    ;; Let's try to look up Blade Runner by name and attributes.
    (first                ; Return the best result.
      (only-matching      ; Exclude any that don't match.
        (mql-reconcile { "/type/object/name" "Blade Runner",
          "/type/object/type" "/film/film",
          "/film/film/starring/actor" ["Harrison Ford", "Rutger Hauer"],
          "/film/film/starring/character" ["Rick Deckard", "Roy Batty"],
          "/film/film/director" {
            "name" "Ridley Scott",
            "id" "/guid/9202a8c04000641f8000000000032ded"
          },
          "/film/film/release_date_s" "1981"
          })))

    ;; Run a read query.
    (mql-read { "name" "Blade Runner" "/type/object/type" [] })

    ;; Make more than one query at a time.
    (mql-read [
      { "id" "/guid/9202a8c04000641f8000000000009e89"
        "name" nil }

      { "id" "/guid/9202a8c04000641f8000000000032ded"
        "/type/object/type" [] }])

    ;; Fetch status info.
    (prn (mql-version))
    (prn (mql-status))
