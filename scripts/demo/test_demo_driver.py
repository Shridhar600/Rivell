import unittest

import demo_driver


class StubApi:
    def __init__(self, responses):
        self.responses = responses

    def get(self, path):
        return self.responses[path]


class EmptyDatabaseTest(unittest.TestCase):

    def test_phase_a_refuses_a_database_that_already_has_rows(self):
        empty = {"/api/contracts?limit=1": {"items": []},
                 "/api/knowledge/artifacts?limit=1": [],
                 "/api/reviews?limit=1": {"items": []}}
        for path in empty:
            with self.subTest(path=path):
                responses = dict(empty)
                responses[path] = ([{"id": "x"}] if path == "/api/knowledge/artifacts?limit=1"
                                   else {"items": [{"id": "x"}]})
                with self.assertRaisesRegex(demo_driver.DemoError, "database is not empty"):
                    demo_driver.assert_empty_database(StubApi(responses))

    def test_phase_a_accepts_three_empty_lists(self):
        api = StubApi({"/api/contracts?limit=1": {"items": []},
                       "/api/knowledge/artifacts?limit=1": [],
                       "/api/reviews?limit=1": {"items": []}})
        demo_driver.assert_empty_database(api)


class TapeMissTest(unittest.TestCase):

    def test_a_recorded_miss_stops_the_run(self):
        for health in ({"misses": [{"path": "/v1/systemone"}]},
                       {"misses": [], "unrouted": [{"method": "POST",
                                                   "path": "/v1/systemone"}]}):
            with self.subTest(health=health):
                with self.assertRaisesRegex(demo_driver.DemoError, "replay miss.*systemone"):
                    demo_driver.require_no_misses(health)

    def test_no_misses_is_fine(self):
        demo_driver.require_no_misses({"misses": []})


if __name__ == "__main__":
    unittest.main()
