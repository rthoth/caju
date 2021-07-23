from locust import HttpUser, task, between
import random

current = -1

def next_account():
    global current
    current += 1
    return "AAAA-%d" % (current)

class CajuUser(HttpUser):
    wait_time = between(0.1, 2)

    merchants = [
        ("CINECLUB del Toro           SAO PAULO SP","5815"),
        ("CINECLUB Boytatá            RIB PRETO SP","5815"),
        ("SUPERM MAX                      BELEM PA","5411"),
        ("SUPERM MIN               RIO DE JANEI RJ","5411"),
        ("LANCHONETE PARADA              LAVRAS MG","5812"),
        ("PADARIA 2BROTHERS           SAO PAULO SP","5813")
    ]

    def __init__(self, args):
        self.account = next_account()
        HttpUser.__init__(self, args)
        self.client.post("/account", json={"code": self.account, "meal": 1000, "food": 1000, "culture": 1000, "cash": 1000})

    @task
    def update(self):
        self.client.post("/account", json={"code": self.account, "meal": 1000, "food": 1000, "culture": 1000, "cash": 1000})

    @task(10)
    def authorize(self):
        merchant = random.choice(self.merchants)
        self.client.post("/authorize", json={"account": self.account, "totalAmount": 1, "mcc": merchant[1], "merchant": merchant[0]})