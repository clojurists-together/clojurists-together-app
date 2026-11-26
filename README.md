# Clojurists Together app


## Usage

FIXME: explanation

    $ java -jar app-clojuriststogether-0.1.0-standalone.jar [args]

```
heroku labs:enable runtime-dyno-metadata -a <app name>
```

## Membership picker

Manage the picker in Stripe using metadata:

- On a **product**, set `member_type` to `developer` or `company`. Products without a recognised value appear under Other memberships.
- On a **price**, set `public=false` to hide it from the picker while keeping it available for manual use. Removing the key or setting `public=true` shows it again.

Within each group, membership cards are sorted by their lowest visible monthly-equivalent price. Billing options stay together within each card; equal monthly equivalents are ordered by the amount charged per payment.

## License

Copyright © 2020 Clojurists Together Foundation

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
