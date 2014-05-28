# aspire-rollout

Munge a bunch of data to rollout galleon (aspire)

## Usage

*WIP*

Create a rollout-conf.edn file (an example file is included at rollout-conf-dist.edn):

```clj
{:moodle-db {:subprotocol "postgresql"
             :subname "//kirk-direct/moodletestX_vlacs_org"
             :user "user"
             :password "password"}
 :comps-path "~/rollout-files/comps"
 :content-path "~/rollout-files/ContentExport.csv"}
```

## License

Copyright © 2014 VLACS

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
