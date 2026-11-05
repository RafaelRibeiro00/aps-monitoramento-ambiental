import argparse
import contextlib
import http.server
import io
import json
import tempfile
import threading
import unittest
from pathlib import Path
from carga.loadgen import executar, percentil, resumo, validar_local, corpo

class GeradorTest(unittest.TestCase):
    def test_percentis_e_erros(self):
        self.assertEqual(percentil(list(range(1,101)),95),95)
        self.assertIsNone(percentil([],50))
        r=[dict(latencia_ms=10,erro='',pod='a',versao='v1',status=200),
           dict(latencia_ms=100,erro='timeout',pod='',versao='',status=0)]
        s=resumo(r,2)
        self.assertEqual(s['erros_pct'],50)
        self.assertEqual(s['vazao_ok_s'],.5)
        self.assertEqual(s['p95_ms'],100)
    def test_seguranca_e_sensores(self):
        validar_local('http://127.0.0.1:8088/manancial/leituras')
        with self.assertRaises(ValueError): validar_local('http://192.0.2.1')
        rows=[corpo('alagamento',1,s,'teste') for s in range(3)]
        self.assertEqual(len({r['ponto_id'] for r in rows}),1)
        self.assertEqual(len({r['tipo'] for r in rows}),3)
    def test_medicao_http_exportacao(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(503 if self.path=='/erro' else 200)
                self.send_header('X-Pod','pod-teste');self.send_header('X-Versao','v1')
                self.end_headers();self.wfile.write(b'{}')
            def log_message(self,*a): pass
        server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                for route in ['', '/erro']:
                    a=argparse.Namespace(url=f'http://127.0.0.1:{server.server_port}'+route,
                        saida=str(Path(tmp)/('erro' if route else 'ok')),degraus=[1,2],
                        concorrencia=2,duracao=.25,aquecimento=.05,rampa=0,timeout=1,
                        metodo='GET',servico='manancial',corpo=None)
                    with contextlib.redirect_stdout(io.StringIO()): result=executar(a)
                    self.assertGreater(result['total']['requisicoes'],0)
                    self.assertEqual(result['total']['erros_pct'],100 if route else 0)
                    self.assertIn('pod-teste',result['total']['por_pod'])
                    self.assertEqual(len(result['degraus']),2)
                    self.assertTrue(Path(a.saida+'-segundos.csv').exists())
        finally: server.shutdown();server.server_close()

if __name__=='__main__': unittest.main()
